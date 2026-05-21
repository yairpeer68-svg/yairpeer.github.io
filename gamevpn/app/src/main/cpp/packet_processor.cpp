#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <atomic>
#include <arpa/inet.h>

#define TAG "PacketEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

#pragma pack(push, 1)
struct IpHdr  { uint8_t ver_ihl, tos; uint16_t tot_len, id, frag_off; uint8_t ttl, proto; uint16_t check; uint32_t saddr, daddr; };
struct TcpHdr { uint16_t sport, dport; uint32_t seq, ack; uint8_t off, flags; uint16_t win, check, urg; };
struct UdpHdr { uint16_t sport, dport, len, check; };
struct DnsHdr { uint16_t id, flags, qdcount, ancount, nscount, arcount; };
#pragma pack(pop)

#define PROTO_TCP   6
#define PROTO_UDP  17
#define PORT_DNS   htons(53)
#define FLAG_RST   0x04
#define FLAG_ACK   0x10
// DSCP EF (Expedited Forwarding) = 0xB8 = 1011_1000
#define DSCP_EF    0xB8

static const uint32_t CF_DNS  = 0x01010101; // 1.1.1.1 (big-endian on wire)

// ── Counters ─────────────────────────────────────────────────────────────────
static std::atomic<uint64_t> pkt_total{0};
static std::atomic<uint64_t> pkt_lost{0};
static std::atomic<uint64_t> bytes_total{0};
static std::atomic<uint64_t> dns_redirected{0};
static std::atomic<uint64_t> dns_blocked{0};

// ── Checksum ─────────────────────────────────────────────────────────────────
static uint16_t chksum(const void* d, int len) {
    uint32_t s = 0;
    const uint16_t* p = (const uint16_t*)d;
    while (len > 1) { s += *p++; len -= 2; }
    if (len) s += *(const uint8_t*)p;
    while (s >> 16) s = (s & 0xFFFF) + (s >> 16);
    return (uint16_t)~s;
}

static uint16_t tcp_chk(const IpHdr* ip, const TcpHdr* tcp, int tlen) {
    struct { uint32_t s, d; uint8_t z, p; uint16_t l; }
        ps = { ip->saddr, ip->daddr, 0, PROTO_TCP, htons(tlen) };
    uint32_t s = 0;
    const uint16_t* p = (const uint16_t*)&ps;
    for (int i = 0; i < (int)sizeof(ps) / 2; i++) s += p[i];
    p = (const uint16_t*)tcp;
    int l = tlen;
    while (l > 1) { s += *p++; l -= 2; }
    if (l) s += *(const uint8_t*)p;
    while (s >> 16) s = (s & 0xFFFF) + (s >> 16);
    return (uint16_t)~s;
}

static uint16_t udp_chk(const IpHdr* ip, const UdpHdr* udp, int ulen) {
    struct { uint32_t s, d; uint8_t z, p; uint16_t l; }
        ps = { ip->saddr, ip->daddr, 0, PROTO_UDP, htons(ulen) };
    uint32_t s = 0;
    const uint16_t* p = (const uint16_t*)&ps;
    for (int i = 0; i < (int)sizeof(ps) / 2; i++) s += p[i];
    p = (const uint16_t*)udp;
    int l = ulen;
    while (l > 1) { s += *p++; l -= 2; }
    if (l) s += *(const uint8_t*)p;
    while (s >> 16) s = (s & 0xFFFF) + (s >> 16);
    uint16_t r = (uint16_t)~s;
    return r ? r : 0xFFFF; // RFC 768: 0 means no checksum; use 0xFFFF if computed sum is 0
}

// ── DNS name parser ───────────────────────────────────────────────────────────
static int dns_name(const uint8_t* buf, int blen, int off, char* out, int osz) {
    int pos = off, wr = 0, jmp = 0;
    while (pos < blen && jmp < 10) {
        uint8_t l = buf[pos];
        if (!l) { pos++; break; }
        if ((l & 0xC0) == 0xC0) {
            if (pos + 1 >= blen) return -1;
            pos = ((l & 0x3F) << 8) | buf[pos + 1];
            jmp++;
            continue;
        }
        if (wr > 0 && wr < osz - 1) out[wr++] = '.';
        for (int i = 0; i < l && pos + 1 + i < blen && wr < osz - 1; i++)
            out[wr++] = (char)buf[pos + 1 + i];
        pos += l + 1;
    }
    if (wr < osz) out[wr] = '\0';
    return pos;
}

// Slow/distant matchmaking servers — NXDOMAIN block list
static const char* BLOCKED[] = {
    "cod-eu-matchmaking", "cod-us-east", "cod-us-west",
    "activision.com.edgekey.net", "s3-eu-west", "d2dzik3ii2sd1y",
    "pdp.lol.riotgames.com", "euw1.api.riotgames",
    nullptr
};

static bool is_blocked(const char* n) {
    for (int i = 0; BLOCKED[i]; i++)
        if (strstr(n, BLOCKED[i])) return true;
    return false;
}

static int nxdomain(uint8_t* d, int len) {
    if (len < (int)sizeof(DnsHdr)) return -1;
    DnsHdr* h = (DnsHdr*)d;
    // Set QR=1, AA=1, RCODE=3 (NXDOMAIN), RA=1
    h->flags     = htons(0x8583);
    h->ancount   = 0;
    h->nscount   = 0;
    h->arcount   = 0;
    return len;
}

// ── QoS: DSCP EF stamp ───────────────────────────────────────────────────────
static void stamp_dscp(IpHdr* ip) {
    if ((ip->tos & 0xFC) != DSCP_EF) {
        ip->tos   = (ip->tos & 0x03) | DSCP_EF;
        ip->check = 0;
        ip->check = chksum(ip, (ip->ver_ihl & 0xF) * 4);
    }
}

// ── TCP RST builder ───────────────────────────────────────────────────────────
static int build_rst(const uint8_t* orig, int olen, uint8_t* out, int osz) {
    int ihl = (orig[0] & 0xF) * 4;
    if (olen < ihl + (int)sizeof(TcpHdr) || osz < ihl + (int)sizeof(TcpHdr)) return -1;
    const IpHdr*  ip  = (const IpHdr*)orig;
    const TcpHdr* tcp = (const TcpHdr*)(orig + ihl);
    if (ip->proto != PROTO_TCP) return -1;
    memcpy(out, orig, ihl + sizeof(TcpHdr));
    IpHdr*  rip = (IpHdr*)out;
    TcpHdr* rt  = (TcpHdr*)(out + ihl);
    rip->saddr  = ip->daddr; rip->daddr = ip->saddr;
    rt->sport   = tcp->dport; rt->dport = tcp->sport;
    rt->flags   = FLAG_RST | FLAG_ACK;
    rt->seq     = tcp->ack;
    rt->ack     = htonl(ntohl(tcp->seq) + 1);
    rt->win     = 0; rt->urg = 0;
    int tlen    = sizeof(TcpHdr);
    rip->tot_len = htons(ihl + tlen);
    rip->check  = 0; rip->check = chksum(rip, ihl);
    rt->check   = 0; rt->check  = tcp_chk(rip, rt, tlen);
    return ihl + tlen;
}

// ── JNI ──────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_processPacket(
    JNIEnv* env, jobject, jbyteArray pkt, jint len, jboolean isGamePkt) {

    jbyte* buf = env->GetByteArrayElements(pkt, nullptr);
    if (!buf || len < 20) {
        if (buf) env->ReleaseByteArrayElements(pkt, buf, JNI_ABORT);
        return 0;
    }
    pkt_total++;
    bytes_total += (uint64_t)len;

    IpHdr* ip  = (IpHdr*)buf;
    int ihl    = (ip->ver_ihl & 0xF) * 4;
    int result = 0;

    // Stamp DSCP EF for all traffic (prioritize at router level)
    stamp_dscp(ip);
    result = 1;

    if (ip->proto == PROTO_UDP && len > ihl + (int)sizeof(UdpHdr)) {
        UdpHdr* udp = (UdpHdr*)((uint8_t*)buf + ihl);
        if (udp->dport == PORT_DNS) {
            uint8_t* dns  = (uint8_t*)buf + ihl + sizeof(UdpHdr);
            int      dlen = len - ihl - sizeof(UdpHdr);
            if (dlen > (int)sizeof(DnsHdr)) {
                char domain[256] = {};
                dns_name(dns, dlen, sizeof(DnsHdr), domain, sizeof(domain));
                if (domain[0] && is_blocked(domain)) {
                    LOGW("NXDOMAIN: %s", domain);
                    dns_blocked++;
                    nxdomain(dns, dlen);
                    // Swap src/dst to make it look like a reply
                    uint32_t ti = ip->saddr; ip->saddr = ip->daddr; ip->daddr = ti;
                    uint16_t tp = udp->sport; udp->sport = udp->dport; udp->dport = tp;
                    ip->check  = 0; ip->check  = chksum(ip, ihl);
                    udp->check = 0; udp->check = udp_chk(ip, udp, ntohs(udp->len));
                    result = 2;
                } else if (ip->daddr != CF_DNS) {
                    // Redirect to Cloudflare DoH-capable DNS
                    ip->daddr  = CF_DNS;
                    ip->check  = 0; ip->check  = chksum(ip, ihl);
                    udp->check = 0; // Recalculate
                    udp->check = udp_chk(ip, udp, ntohs(udp->len));
                    dns_redirected++;
                    result = 3;
                }
            }
        }
    }

    env->SetByteArrayRegion(pkt, 0, len, buf);
    env->ReleaseByteArrayElements(pkt, buf, 0);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_buildRst(
    JNIEnv* env, jobject, jbyteArray original, jint length) {
    jbyte* orig = env->GetByteArrayElements(original, nullptr);
    if (!orig) return nullptr;
    uint8_t rst[256];
    int rlen = build_rst((const uint8_t*)orig, length, rst, sizeof(rst));
    env->ReleaseByteArrayElements(original, orig, JNI_ABORT);
    if (rlen <= 0) return nullptr;
    jbyteArray r = env->NewByteArray(rlen);
    env->SetByteArrayRegion(r, 0, rlen, (jbyte*)rst);
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_recordLoss(JNIEnv*, jobject) {
    pkt_lost++;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_getPacketLoss(JNIEnv*, jobject) {
    uint64_t tot = pkt_total.load(), lost = pkt_lost.load();
    return tot > 0 ? (float)lost / tot * 100.f : 0.f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_resetCounters(JNIEnv*, jobject) {
    pkt_total = 0; pkt_lost = 0; bytes_total = 0;
    dns_redirected = 0; dns_blocked = 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_getVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("PacketEngine v4.0 | DSCP-EF+DNS-CF+NXDOMAIN+RST+UDPchk+Counters");
}

// ── Thread affinity ───────────────────────────────────────────────────────────
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_pinToBigCores(JNIEnv*, jobject) {
    int numCores = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (numCores <= 0) return -1;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int bigStart = numCores / 2;
    for (int i = bigStart; i < numCores; i++) CPU_SET(i, &cpuset);
    int r = pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset);
    LOGI("Thread affinity → cores %d-%d (r=%d)", bigStart, numCores - 1, r);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_setRealtimeScheduling(JNIEnv*, jobject) {
    struct sched_param sp = {}; sp.sched_priority = 10;
    int r = sched_setscheduler(0, SCHED_FIFO, &sp);
    if (r == 0) LOGI("Thread → SCHED_FIFO priority 10");
    else {
        LOGW("SCHED_FIFO failed (need CAP_SYS_NICE), falling back to SCHED_RR");
        sp.sched_priority = 1;
        sched_setscheduler(0, SCHED_RR, &sp);
    }
    setpriority(PRIO_PROCESS, 0, -10);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_getNumCores(JNIEnv*, jobject) {
    return (jint)sysconf(_SC_NPROCESSORS_CONF);
}

// ── Socket tuning ─────────────────────────────────────────────────────────────
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_tuneSocket(JNIEnv*, jobject, jint fd) {
    int r = 0;
    int on = 1;
    r |= setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));
    int rcvbuf = 256 * 1024;
    r |= setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
    int sndbuf = 128 * 1024;
    r |= setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
    int prio = 6;
    setsockopt(fd, SOL_SOCKET, SO_PRIORITY, &prio, sizeof(prio)); // ignore error (may need root)
    // IP_TOS: set DSCP EF at socket level
    int tos = DSCP_EF;
    setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));
    LOGI("Socket tuned: nodelay + rcv=256K + snd=128K + DSCP_EF (fd=%d, r=%d)", fd, r);
    return r;
}

// ── madvise: keep game pages in RAM ─────────────────────────────────────────
#include <sys/mman.h>
#include <fcntl.h>
#include <stdio.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_adviseKeepInRam(JNIEnv*, jobject, jint pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE* f = fopen(path, "r");
    if (!f) { LOGW("Cannot open /proc/%d/maps (no permission)", pid); return -1; }
    char line[512];
    int advised = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, "r-xp") && !strstr(line, "rw-p")) continue;
        if (strstr(line, "/system/") || strstr(line, "/apex/") ||
            strstr(line, "/data/app/~~")) continue; // Skip APK base mappings
        unsigned long start = 0, end = 0;
        if (sscanf(line, "%lx-%lx", &start, &end) == 2 && end > start) {
            size_t sz = end - start;
            if (sz > 0 && sz < 256UL * 1024 * 1024) { // cap at 256MB
                if (madvise((void*)start, sz, MADV_WILLNEED) == 0) advised++;
            }
        }
    }
    fclose(f);
    LOGI("madvise WILLNEED: %d regions for pid=%d", advised, pid);
    return advised;
}

// ── High priority mode ────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_yourname_gamemodevpn_PacketEngine_setHighPriorityMode(JNIEnv*, jobject, jboolean enable) {
    if (enable) {
        setpriority(PRIO_PROCESS, 0, -10);
        struct sched_param sp = {}; sp.sched_priority = 1;
        if (sched_setscheduler(0, SCHED_FIFO, &sp) == 0)
            LOGI("Thread → SCHED_FIFO priority 1");
        else
            sched_setscheduler(0, SCHED_RR, &sp);
    } else {
        struct sched_param sp = {}; sp.sched_priority = 0;
        sched_setscheduler(0, SCHED_OTHER, &sp);
        setpriority(PRIO_PROCESS, 0, 0);
    }
}
