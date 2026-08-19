package com.magen.family.mitm;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.magen.family.filter.ContentFilter;
import com.magen.family.filter.DomainVerdict;
import com.magen.family.server.ContentIncidentReporter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Local-only managed HTTP/HTTPS inspection proxy.
 *
 * One authenticated loopback listener is used:
 *  - 127.0.0.1:18083: private transparent TLS listener used only by Magen's TcpRelay after SNI.
 * A normal explicit proxy is deliberately not exposed because loopback TCP is reachable by other
 * Android app sandboxes and would otherwise become a protected-socket VPN bypass.
 *
 * Privacy/security invariants:
 *  - no public listener;
 *  - no request/response bodies, cookies, tokens or complete URLs are persisted/logged;
 *  - proxy-only authorization headers are stripped before forwarding;
 *  - sensitive identity/payment/health destinations are tunnelled without decryption;
 *  - certificate pinning is never defeated. A TLS/protocol failure creates only a hashed,
 *    short-lived compatibility fallback, and the next connection is tunnelled end-to-end;
 *  - upstream TLS is validated with the Android platform trust manager and HTTPS hostname check;
 *  - only public DNS destinations on 80/443 are reachable, preventing localhost/LAN SSRF.
 */
public final class HttpsInspectionProxy {
    private static final String TAG = "MagenHttpsProxy";
    /** Explicit proxy listener is intentionally disabled: another sandboxed app can reach loopback. */
    public static final int TRANSPARENT_PORT = 18083;
    private static final int HEADER_LIMIT = 32768;
    private static final int PREFIX_LIMIT = 512;
    private static final long MAX_SINGLE_REQUEST_BODY = 64L * 1024L * 1024L;
    private static final int CHUNK_LINE_LIMIT = 8192;
    private static final int INSPECTED_IDLE_TIMEOUT_MS = 60_000;
    private static volatile HttpsInspectionProxy INSTANCE;

    private final Context app;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> activeClients = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        8, 64, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
        r -> { Thread t = new Thread(r, "MagenHttpsProxyWorker"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());

    private final String transparentToken = newTransparentToken();
    private volatile ServerSocket transparentServer;
    private volatile Thread transparentAcceptThread;

    private HttpsInspectionProxy(Context c) { app = c.getApplicationContext(); }

    public static HttpsInspectionProxy get(Context c) {
        HttpsInspectionProxy x = INSTANCE;
        if (x != null) return x;
        synchronized (HttpsInspectionProxy.class) {
            if (INSTANCE == null) INSTANCE = new HttpsInspectionProxy(c);
            return INSTANCE;
        }
    }

    public boolean isRunning() {
        return running.get() && transparentServer != null && !transparentServer.isClosed();
    }

    public synchronized boolean start() {
        if (isRunning()) return true;
        stop();
        try {
            transparentServer = bindLoopback(TRANSPARENT_PORT);
            running.set(true);
            transparentAcceptThread = new Thread(() -> acceptLoop(transparentServer), "MagenHttpsTransparentAccept");
            transparentAcceptThread.setDaemon(true);
            transparentAcceptThread.start();
            Log.i(TAG, "authenticated transparent HTTPS inspection listener ready on loopback");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "proxy bind failed: " + e.getClass().getSimpleName());
            stop();
            return false;
        }
    }

    private static ServerSocket bindLoopback(int port) throws Exception {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), port), 64);
        return s;
    }

    public synchronized void stop() {
        running.set(false);
        closeServer(transparentServer); transparentServer = null;
        if (transparentAcceptThread != null) transparentAcceptThread.interrupt();
        transparentAcceptThread = null;
        for (Socket s : activeClients) try { s.close(); } catch (Exception ignored) {}
        activeClients.clear();
    }

    private static void closeServer(ServerSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    private void acceptLoop(ServerSocket listener) {
        while (running.get() && listener != null && !listener.isClosed()) {
            Socket client = null;
            try {
                client = listener.accept();
                client.setTcpNoDelay(true);
                activeClients.add(client);
                MitmRuntimeState.proxyConnection();
                final Socket accepted = client;
                workers.execute(() -> {
                    try {
                        handleTransparent(accepted);
                    } finally {
                        activeClients.remove(accepted);
                        try { accepted.close(); } catch (Exception ignored) {}
                    }
                });
            } catch (RejectedExecutionException e) {
                if (client != null) try { client.close(); } catch (Exception ignored) {}
                MitmRuntimeState.failure();
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "accept: " + e.getClass().getSimpleName());
            }
        }
    }

    private void handleExplicit(Socket client) {
        try {
            client.setSoTimeout(12000);
            byte[] hdr = readHeader(client.getInputStream(), HEADER_LIMIT);
            if (hdr == null) return;
            String first = firstLine(hdr);
            if (first == null) return;
            String[] p = first.split(" ", 3);
            if (p.length < 3) return;
            String method = p[0].toUpperCase(Locale.ROOT);
            if ("CONNECT".equals(method)) {
                HostPort hp = parseAuthority(p[1], 443);
                handleConnect(client, hp.host, hp.port);
                return;
            }
            handlePlainHttp(client, hdr, method, p[1]);
        } catch (Exception e) {
            MitmRuntimeState.failure();
            Log.d(TAG, "explicit proxy flow ended: " + e.getClass().getSimpleName());
        }
    }

    public String transparentPreamble(String host) {
        if (!validDnsHost(host)) throw new IllegalArgumentException("invalid transparent host");
        return "MAGEN2 " + transparentToken + " " + host + "\n";
    }

    private boolean constantTimeTokenEquals(String supplied) {
        return supplied != null && MessageDigest.isEqual(
            transparentToken.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static String newTransparentToken() {
        byte[] b=new byte[16]; new SecureRandom().nextBytes(b);
        StringBuilder out=new StringBuilder(32);
        for(byte x:b) out.append(String.format(Locale.US,"%02x",x&0xff));
        return out.toString();
    }

    /**
     * Private authenticated protocol: "MAGEN2 <128-bit-token> <dns-host>\n" + original TLS stream.
     * Loopback alone is not an Android security boundary; any sandboxed app can open 127.0.0.1.
     * The per-process random token prevents the listener from becoming a device-local VPN bypass.
     */
    private void handleTransparent(Socket client) {
        try {
            client.setSoTimeout(6000);
            String prefix = readAsciiLine(client.getInputStream(), PREFIX_LIMIT);
            if (prefix == null || !prefix.startsWith("MAGEN2 ")) return;
            int split=prefix.indexOf(' ',7);
            if(split<=7)return;
            String supplied=prefix.substring(7,split);
            if(!constantTimeTokenEquals(supplied))return;
            String host = IDN.toASCII(prefix.substring(split+1).trim().toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES);
            if (!validDnsHost(host)) return;

            if (DomainVerdict.isBlocked(app, host)) {
                MitmRuntimeState.block();
                ContentIncidentReporter.reportMitmBlock(app, host, "HTTPS_TRANSPARENT_DOMAIN");
                return;
            }
            if (!MitmPolicy.shouldIntercept(app, host)) {
                MitmRuntimeState.tunnel();
                tunnelTransparent(client, host, 443);
                return;
            }

            MitmCertificateClient.Material material;
            SSLSocket upstream;
            try {
                material = MitmCertificateClient.materialFor(app, host);
                upstream = openTlsUpstream(host, 443);
            } catch (Exception e) {
                MitmRuntimeState.failure();
                MitmRuntimeState.tunnel();
                tunnelTransparent(client, host, 443);
                return;
            }

            try (SSLSocket up = upstream) {
                SSLSocket down = null;
                try {
                    down = createDownstreamTls(material, client, host);
                    down.startHandshake();
                    MitmRuntimeState.intercept();
                    inspectFirstRequestThenPipe(down, up, host);
                } catch (Exception e) {
                    MitmPolicy.markFallback(app, host, "TLS_PINNING_OR_PROTOCOL");
                    MitmRuntimeState.failure();
                } finally {
                    try { if (down != null) down.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            MitmRuntimeState.failure();
            Log.d(TAG, "transparent proxy flow ended: " + e.getClass().getSimpleName());
        }
    }

    private void handleConnect(Socket client, String host, int port) throws Exception {
        if (port != 443 || !validDnsHost(host)) { writeSimple(client, 403, "Proxy target rejected"); return; }
        if (DomainVerdict.isBlocked(app, host)) {
            MitmRuntimeState.block();
            ContentIncidentReporter.reportMitmBlock(app, host, "HTTPS_CONNECT_DOMAIN");
            writeSimple(client, 451, "Blocked by Magen");
            return;
        }
        if (!MitmPolicy.shouldIntercept(app, host)) {
            MitmRuntimeState.tunnel();
            tunnelConnect(client, host, port);
            return;
        }

        // Validate/prepare upstream before acknowledging CONNECT so pre-handshake failures can
        // still fall back to a normal encrypted tunnel on this very connection.
        MitmCertificateClient.Material material;
        SSLSocket upstream;
        try {
            material = MitmCertificateClient.materialFor(app, host);
            upstream = openTlsUpstream(host, port);
        } catch (Exception e) {
            MitmRuntimeState.failure();
            MitmRuntimeState.tunnel();
            tunnelConnect(client, host, port);
            return;
        }

        try (SSLSocket up = upstream) {
            OutputStream rawOut = client.getOutputStream();
            rawOut.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: Magen\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            rawOut.flush();
            SSLSocket down = null;
            try {
                down = createDownstreamTls(material, client, host);
                down.startHandshake();
                MitmRuntimeState.intercept();
                inspectFirstRequestThenPipe(down, up, host);
            } catch (Exception e) {
                MitmPolicy.markFallback(app, host, "TLS_PINNING_OR_PROTOCOL");
                MitmRuntimeState.failure();
            } finally {
                try { if (down != null) down.close(); } catch (Exception ignored) {}
            }
        }
    }

    private SSLSocket createDownstreamTls(MitmCertificateClient.Material material, Socket client, String host) throws Exception {
        SSLSocket down = (SSLSocket) material.sslContext.getSocketFactory().createSocket(client, host, 443, false);
        down.setUseClientMode(false);
        hardenTlsProtocols(down);
        down.setSoTimeout(12000); // only until the first application request arrives
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SSLParameters sp = down.getSSLParameters();
            sp.setApplicationProtocols(new String[]{"http/1.1"});
            down.setSSLParameters(sp);
        }
        return down;
    }

    private void inspectFirstRequestThenPipe(SSLSocket clientTls, SSLSocket upstreamTls, String host) throws Exception {
        InputStream cin = clientTls.getInputStream(); OutputStream cout = clientTls.getOutputStream();
        InputStream uin = upstreamTls.getInputStream(); OutputStream uout = upstreamTls.getOutputStream();
        byte[] first = readHeader(cin, HEADER_LIMIT);
        if (first == null) { MitmPolicy.markFallback(app, host, "NO_HTTP_REQUEST_AFTER_TLS"); return; }
        String line = firstLine(first);
        String target = "/";
        if (line == null) { MitmPolicy.markFallback(app, host, "NON_HTTP_AFTER_TLS"); return; }
        String[] parts = line.split(" ", 3);
        if (parts.length < 3 || "PRI".equals(parts[0])) {
            MitmPolicy.markFallback(app, host, "HTTP2_OR_NON_HTTP");
            return;
        }
        String method=parts[0].toUpperCase(Locale.ROOT);
        if (!("GET".equals(method)||"HEAD".equals(method)||"POST".equals(method)||"PUT".equals(method)
            ||"DELETE".equals(method)||"OPTIONS".equals(method)||"PATCH".equals(method))) {
            MitmPolicy.markFallback(app,host,"UNSUPPORTED_HTTP_METHOD"); return;
        }
        target = parts[1];
        if (!(target.startsWith("/") || ("OPTIONS".equals(method) && "*".equals(target)))) {
            MitmPolicy.markFallback(app,host,"NON_ORIGIN_FORM_REQUEST"); return;
        }
        String requestHost=extractHostHeader(first);
        if (requestHost==null || !host.equalsIgnoreCase(requestHost)) {
            // Do not let TLS SNI/HTTP Host disagreement become a domain-fronting bypass.
            MitmRuntimeState.block();
            ContentIncidentReporter.reportMitmBlock(app,host,"HTTPS_HOST_MISMATCH");
            writeTlsBlock(cout);
            return;
        }
        if (target.startsWith("/")) {
            // In-memory decision only. Query/header/body data are never logged or sent to the VPS.
            ContentFilter cf = new ContentFilter(app);
            if (cf.shouldBlock("https://" + host + target)) {
                MitmRuntimeState.block();
                ContentIncidentReporter.reportMitmBlock(app, host, "HTTPS_PATH_LOCAL");
                writeTlsBlock(cout);
                return;
            }
        }

        // Force one HTTP/1.1 request per TLS connection so keep-alive cannot carry later unchecked
        // paths. The body is streamed, never buffered. Proxy-only credential metadata is removed.
        byte[] sanitized = rewriteRequestHeader(first, target, host, true);
        clientTls.setSoTimeout(INSPECTED_IDLE_TIMEOUT_MS);
        upstreamTls.setSoTimeout(INSPECTED_IDLE_TIMEOUT_MS);
        uout.write(sanitized); uout.flush();
        relaySingleHttpRequest(clientTls, upstreamTls, cin, uout, uin, cout, first);
    }

    private void tunnelConnect(Socket client, String host, int port) throws Exception {
        Socket upstream = openPublicSocket(host, port);
        try (Socket up = upstream) {
            OutputStream o = client.getOutputStream();
            o.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: Magen\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            o.flush();
            client.setSoTimeout(0); up.setSoTimeout(0);
            pipeBidirectional(client, up, client.getInputStream(), up.getOutputStream(), up.getInputStream(), o);
        }
    }

    private void tunnelTransparent(Socket client, String host, int port) throws Exception {
        Socket upstream = openPublicSocket(host, port);
        try (Socket up = upstream) {
            client.setSoTimeout(0); up.setSoTimeout(0);
            pipeBidirectional(client, up, client.getInputStream(), up.getOutputStream(), up.getInputStream(), client.getOutputStream());
        }
    }

    private void handlePlainHttp(Socket client, byte[] hdr, String method, String target) throws Exception {
        if (!("GET".equals(method) || "HEAD".equals(method) || "POST".equals(method) || "PUT".equals(method)
            || "DELETE".equals(method) || "OPTIONS".equals(method) || "PATCH".equals(method))) {
            writeSimple(client, 405, "Method not allowed"); return;
        }
        java.net.URI uri;
        try { uri = new java.net.URI(target); }
        catch (Exception e) { writeSimple(client, 400, "Absolute proxy URL required"); return; }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            writeSimple(client, 400, "Absolute HTTP URL required"); return;
        }
        String host = IDN.toASCII(uri.getHost().toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        if (port != 80 || !validDnsHost(host)) { writeSimple(client, 403, "Proxy target rejected"); return; }
        if (DomainVerdict.isBlocked(app, host) || new ContentFilter(app).shouldBlock(target)) {
            MitmRuntimeState.block();
            ContentIncidentReporter.reportMitmBlock(app, host, "HTTP_URL");
            writeSimple(client, 451, "Blocked by Magen");
            return;
        }
        String originTarget = uri.getRawPath();
        if (originTarget == null || originTarget.isEmpty()) originTarget = "/";
        if (uri.getRawQuery() != null) originTarget += "?" + uri.getRawQuery();
        byte[] sanitized = rewriteRequestHeader(hdr, originTarget, host, true);
        Socket upstream = openPublicSocket(host, port);
        try (Socket up = upstream) {
            client.setSoTimeout(INSPECTED_IDLE_TIMEOUT_MS); up.setSoTimeout(INSPECTED_IDLE_TIMEOUT_MS);
            up.getOutputStream().write(sanitized); up.getOutputStream().flush();
            relaySingleHttpRequest(client, up, client.getInputStream(), up.getOutputStream(),
                up.getInputStream(), client.getOutputStream(), hdr);
        }
    }

    private SSLSocket openTlsUpstream(String host, int port) throws Exception {
        Socket raw = openPublicSocket(host, port);
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        hardenTlsProtocols(s);
        s.setSoTimeout(12000);
        SSLParameters sp = s.getSSLParameters();
        sp.setEndpointIdentificationAlgorithm("HTTPS");
        try { sp.setServerNames(Arrays.asList(new SNIHostName(host))); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sp.setApplicationProtocols(new String[]{"http/1.1"});
        s.setSSLParameters(sp);
        s.startHandshake();
        s.setSoTimeout(0);
        return s;
    }

    private Socket openPublicSocket(String host, int port) throws Exception {
        InetAddress addr = resolvePublic(host);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(addr, port), 6000);
        s.setSoTimeout(15000);
        s.setTcpNoDelay(true);
        return s;
    }

    private static InetAddress resolvePublic(String host) throws Exception {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress a : all) if (isPublic(a)) return a;
        throw new SecurityException("non-public proxy destination");
    }

    private static boolean isPublic(InetAddress a) {
        if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
            || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int x=b[0]&0xff, y=b[1]&0xff, z=b[2]&0xff;
            // RFC special-use / non-Internet destinations. Do not let DNS turn the local
            // inspection proxy into a path to carrier, benchmark or documentation networks.
            if (x==0 || x==10 || x==127 || x>=224) return false;
            if (x==100 && y>=64 && y<=127) return false;              // RFC 6598 CGNAT
            if (x==169 && y==254) return false;                       // link-local
            if (x==172 && y>=16 && y<=31) return false;               // RFC1918
            if (x==192 && y==168) return false;                       // RFC1918
            if (x==192 && y==0 && (z==0 || z==2)) return false;       // IETF/docs
            if (x==198 && (y==18 || y==19)) return false;             // benchmark
            if (x==198 && y==51 && z==100) return false;              // TEST-NET-2
            if (x==203 && y==0 && z==113) return false;               // TEST-NET-3
            return true;
        }
        if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) return false;                   // ULA fc00::/7
            if ((b[0]&0xff)==0x20 && (b[1]&0xff)==0x01 && (b[2]&0xff)==0x0d && (b[3]&0xff)==0xb8) return false; // docs
            return true;
        }
        return false;
    }

    private static boolean validDnsHost(String h) {
        try {
            if (h == null || h.length() > 253 || h.indexOf('*') >= 0 || h.indexOf('/') >= 0
                || h.indexOf('\\') >= 0 || h.indexOf(':') >= 0) return false;
            String a = IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (a.isEmpty() || a.matches("^[0-9.]+$")) return false;
            for (String label : a.split("\\.")) if (label.isEmpty() || label.length() > 63) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    /** Rewrites only protocol metadata; request bodies are never buffered or logged. */
    private static byte[] rewriteRequestHeader(byte[] header, String target, String host, boolean forceClose) throws Exception {
        String raw = new String(header, StandardCharsets.ISO_8859_1);
        String[] lines = raw.split("\\r\\n", -1);
        if (lines.length == 0) throw new SecurityException("empty request");
        String[] first = lines[0].split(" ", 3);
        if (first.length < 3 || !first[2].matches("HTTP/1\\.[01]")) throw new SecurityException("bad request line");
        StringBuilder out = new StringBuilder();
        out.append(first[0]).append(' ').append(target).append(' ').append(first[2]).append("\r\n");
        boolean haveHost = false;
        int hostCount=0;
        String contentLength=null;
        boolean transferEncoding=false;
        String transferEncodingValue=null;
        java.util.HashSet<String> connectionNamed=new java.util.HashSet<>();

        // First pass: validate syntax and collect Connection-nominated hop-by-hop header names.
        for (int i=1;i<lines.length;i++) {
            String line=lines[i]; if(line.isEmpty()) continue;
            if(Character.isWhitespace(line.charAt(0))) throw new SecurityException("obsolete folded header rejected");
            int colon=line.indexOf(':');
            if(colon<=0) throw new SecurityException("malformed header");
            String name=line.substring(0,colon).trim().toLowerCase(Locale.ROOT);
            if(!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw new SecurityException("invalid header name");
            String value=line.substring(colon+1).trim();
            if(value.indexOf('\0')>=0) throw new SecurityException("invalid header value");
            if("connection".equals(name)) for(String t:value.split(",")) {
                String n=t.trim().toLowerCase(Locale.ROOT); if(!n.isEmpty()) connectionNamed.add(n);
            }
            if("content-length".equals(name)) {
                if(!value.matches("[0-9]{1,18}")) throw new SecurityException("invalid content-length");
                if(contentLength!=null&&!contentLength.equals(value)) throw new SecurityException("conflicting content-length");
                contentLength=value;
            }
            if("transfer-encoding".equals(name)) {
                if(transferEncoding)throw new SecurityException("multiple transfer-encoding headers");
                transferEncoding=true; transferEncodingValue=value.toLowerCase(Locale.ROOT);
                if(!"chunked".equals(transferEncodingValue))throw new SecurityException("unsupported transfer-encoding");
            }
        }
        if(contentLength!=null&&transferEncoding) throw new SecurityException("content-length/transfer-encoding ambiguity");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i]; if(line.isEmpty()) continue;
            int colon=line.indexOf(':');
            String name=line.substring(0,colon).trim().toLowerCase(Locale.ROOT);
            if ("proxy-authorization".equals(name) || "proxy-connection".equals(name)
                || "proxy-authenticate".equals(name) || "connection".equals(name)
                || "keep-alive".equals(name) || connectionNamed.contains(name)) continue;
            if ("host".equals(name)) { haveHost=true; hostCount++; if(hostCount>1) throw new SecurityException("multiple host headers"); continue; }
            if ("te".equals(name) || "trailer".equals(name) || "upgrade".equals(name)) continue;
            out.append(line).append("\r\n");
        }
        // Canonicalize Host to the TLS/absolute-URI destination. Never forward a conflicting Host.
        out.append("Host: ").append(host).append("\r\n");
        if (forceClose) out.append("Connection: close\r\n");
        out.append("\r\n");
        byte[] encoded = out.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (encoded.length > HEADER_LIMIT) throw new SecurityException("rewritten header too large");
        return encoded;
    }

    private static String extractHostHeader(byte[] header) {
        try {
            String raw=new String(header,StandardCharsets.ISO_8859_1);
            String[] lines=raw.split("\\r\\n"); String found=null;
            for(int i=1;i<lines.length;i++){
                int c=lines[i].indexOf(':'); if(c<=0)continue;
                String name=lines[i].substring(0,c).trim(); if(!"host".equalsIgnoreCase(name))continue;
                if(found!=null)return null;
                String value=lines[i].substring(c+1).trim();
                if(value.startsWith("[")||value.indexOf('@')>=0||value.indexOf('/')>=0)return null;
                int colon=value.lastIndexOf(':');
                if(colon>0&&value.indexOf(':')==colon){String ps=value.substring(colon+1);if(!ps.matches("[0-9]{1,5}"))return null;value=value.substring(0,colon);}
                found=IDN.toASCII(value.toLowerCase(Locale.ROOT),IDN.USE_STD3_ASCII_RULES);
            }
            return validDnsHost(found)?found:null;
        }catch(Exception e){return null;}
    }

    private static void hardenTlsProtocols(SSLSocket socket) throws Exception {
        java.util.ArrayList<String> enabled=new java.util.ArrayList<>();
        java.util.HashSet<String> supported=new java.util.HashSet<>(Arrays.asList(socket.getSupportedProtocols()));
        if(supported.contains("TLSv1.3"))enabled.add("TLSv1.3");
        if(supported.contains("TLSv1.2"))enabled.add("TLSv1.2");
        if(enabled.isEmpty())throw new SecurityException("TLS 1.2+ unavailable");
        socket.setEnabledProtocols(enabled.toArray(new String[0]));
    }

    private static byte[] readHeader(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(Math.min(max, 4096));
        int state = 0;
        while (b.size() < max) {
            int x = in.read();
            if (x < 0) return b.size() == 0 ? null : b.toByteArray();
            b.write(x);
            if (state == 0 && x == '\r') state = 1;
            else if (state == 1 && x == '\n') state = 2;
            else if (state == 2 && x == '\r') state = 3;
            else if (state == 3 && x == '\n') return b.toByteArray();
            else state = (x == '\r') ? 1 : 0;
        }
        throw new SecurityException("proxy header too large");
    }

    private static String readAsciiLine(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        while (b.size() < max) {
            int x = in.read();
            if (x < 0) return null;
            if (x == '\n') return new String(b.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
            if (x < 0x20 || x > 0x7e) return null;
            b.write(x);
        }
        throw new SecurityException("transparent prefix too large");
    }

    private static String firstLine(byte[] h) {
        int end = -1;
        for (int i = 0; i + 1 < h.length; i++) if (h[i] == '\r' && h[i + 1] == '\n') { end = i; break; }
        if (end <= 0) return null;
        return new String(h, 0, end, StandardCharsets.ISO_8859_1);
    }

    private static final class HostPort {
        final String host; final int port;
        HostPort(String h, int p) { host = h; port = p; }
    }

    private static HostPort parseAuthority(String a, int def) throws Exception {
        String h = a; int p = def;
        int c = a.lastIndexOf(':');
        if (c > 0 && a.indexOf(':') == c) { h = a.substring(0, c); p = Integer.parseInt(a.substring(c + 1)); }
        h = IDN.toASCII(h.toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES);
        return new HostPort(h, p);
    }

    private static final class BodyFraming {
        final long contentLength;
        final boolean chunked;
        BodyFraming(long length, boolean isChunked) { contentLength=length; chunked=isChunked; }
    }

    /**
     * Relays exactly one HTTP/1.1 request and its response. Client bytes after the first framed
     * request are deliberately never forwarded, so pipelining/keep-alive cannot smuggle a second
     * unchecked path through an already-inspected TLS connection.
     */
    private static void relaySingleHttpRequest(Socket client, Socket upstream,
                                               InputStream cin, OutputStream uout,
                                               InputStream uin, OutputStream cout,
                                               byte[] originalHeader) throws Exception {
        BodyFraming framing=parseBodyFraming(originalHeader);
        CountDownLatch responseDone=new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Exception> responseError=
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread down=new Thread(() -> {
            byte[] buf=new byte[16384];
            try {
                while(true){
                    int n=uin.read(buf);
                    if(n<0)break;
                    if(n>0){cout.write(buf,0,n);cout.flush();}
                }
            } catch(Exception e){ responseError.set(e); }
            finally { responseDone.countDown(); }
        }, "MagenHttpsSingleResponse");
        down.setDaemon(true);
        down.start();

        try {
            if(framing.chunked) relayChunkedBody(cin,uout);
            else if(framing.contentLength>0) copyExactly(cin,uout,framing.contentLength);
            uout.flush();
            // Do not read another byte from cin. Connection: close tells the origin to terminate
            // after this response; any pipelined request remains local and is discarded on close.
            responseDone.await();
            Exception e=responseError.get();
            if(e!=null && !(e instanceof java.net.SocketException)) throw e;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static BodyFraming parseBodyFraming(byte[] header) throws Exception {
        String raw=new String(header,StandardCharsets.ISO_8859_1);
        String[] lines=raw.split("\\r\\n");
        Long cl=null; boolean chunked=false;
        for(int i=1;i<lines.length;i++){
            int c=lines[i].indexOf(':'); if(c<=0)continue;
            String name=lines[i].substring(0,c).trim().toLowerCase(Locale.ROOT);
            String value=lines[i].substring(c+1).trim();
            if("content-length".equals(name)){
                if(!value.matches("[0-9]{1,18}"))throw new SecurityException("invalid content-length");
                long n=Long.parseLong(value);
                if(n<0 || n>MAX_SINGLE_REQUEST_BODY)throw new SecurityException("request body too large");
                if(cl!=null && cl.longValue()!=n)throw new SecurityException("conflicting content-length");
                cl=n;
            } else if("transfer-encoding".equals(name)){
                if(chunked || !"chunked".equalsIgnoreCase(value))throw new SecurityException("unsupported transfer-encoding");
                chunked=true;
            }
        }
        if(cl!=null && chunked)throw new SecurityException("content-length/transfer-encoding ambiguity");
        return new BodyFraming(cl==null?0L:cl.longValue(),chunked);
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws Exception {
        byte[] buf=new byte[16384]; long left=count;
        while(left>0){
            int n=in.read(buf,0,(int)Math.min((long)buf.length,left));
            if(n<0)throw new java.io.EOFException("request body truncated");
            if(n==0)continue;
            out.write(buf,0,n); left-=n;
        }
    }

    private static void relayChunkedBody(InputStream in, OutputStream out) throws Exception {
        long total=0;
        while(true){
            String line=readStrictCrlfLine(in,CHUNK_LINE_LIMIT);
            String sizePart=line; int semi=line.indexOf(';'); if(semi>=0)sizePart=line.substring(0,semi);
            sizePart=sizePart.trim();
            if(!sizePart.matches("[0-9A-Fa-f]{1,16}"))throw new SecurityException("invalid chunk size");
            long n=Long.parseUnsignedLong(sizePart,16);
            if(n>MAX_SINGLE_REQUEST_BODY || total+n>MAX_SINGLE_REQUEST_BODY)throw new SecurityException("chunked body too large");
            out.write(line.getBytes(StandardCharsets.US_ASCII)); out.write('\r'); out.write('\n');
            if(n==0){
                // Trailers are not needed by Magen and are rejected to keep request framing
                // unambiguous and prevent a trailer-based smuggling surface.
                String trailer=readStrictCrlfLine(in,CHUNK_LINE_LIMIT);
                if(!trailer.isEmpty())throw new SecurityException("HTTP trailers not supported");
                out.write('\r'); out.write('\n');
                return;
            }
            copyExactly(in,out,n); total+=n;
            int r=in.read(), lf=in.read();
            if(r!='\r' || lf!='\n')throw new SecurityException("invalid chunk terminator");
            out.write('\r'); out.write('\n'); out.flush();
        }
    }

    private static String readStrictCrlfLine(InputStream in,int max) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        while(b.size()<max){
            int x=in.read(); if(x<0)throw new java.io.EOFException("truncated HTTP line");
            if(x=='\r'){
                int y=in.read(); if(y!='\n')throw new SecurityException("invalid HTTP line ending");
                return new String(b.toByteArray(),StandardCharsets.US_ASCII);
            }
            if(x<' ' || x>0x7e)throw new SecurityException("invalid HTTP line byte");
            b.write(x);
        }
        throw new SecurityException("HTTP line too long");
    }

    private void pipeBidirectional(Socket a, Socket b, InputStream ain, OutputStream bout, InputStream bin, OutputStream aout) {
        CountDownLatch done = new CountDownLatch(2);
        Thread up = new Thread(() -> copyHalf(ain, bout, b, done), "MagenHttpsProxyUp");
        Thread down = new Thread(() -> copyHalf(bin, aout, a, done), "MagenHttpsProxyDown");
        up.setDaemon(true); down.setDaemon(true); up.start(); down.start();
        try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void copyHalf(InputStream in, OutputStream out, Socket destination, CountDownLatch done) {
        byte[] buf = new byte[16384];
        try {
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n > 0) { out.write(buf, 0, n); out.flush(); }
            }
        } catch (Exception ignored) {
        } finally {
            try { destination.shutdownOutput(); } catch (Exception ignored) {}
            done.countDown();
        }
    }

    private static void writeSimple(Socket s, int code, String msg) throws Exception {
        byte[] body = ("<html><body><h2>" + msg + "</h2></body></html>").getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Cache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream(); o.write(h.getBytes(StandardCharsets.US_ASCII)); o.write(body); o.flush();
    }

    private static void writeTlsBlock(OutputStream o) throws Exception {
        byte[] body = "<html><body><h2>Blocked by Magen</h2></body></html>".getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 451 Unavailable For Legal Reasons\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Cache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        o.write(h.getBytes(StandardCharsets.US_ASCII)); o.write(body); o.flush();
    }
}
