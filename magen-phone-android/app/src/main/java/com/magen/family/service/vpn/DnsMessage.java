package com.magen.family.service.vpn;

/**
 * DnsMessage — פרסור מינימלי של שאילתת DNS ובניית תשובת NXDOMAIN.
 *
 * למה צריך את זה:
 *   כדי לחסום ברמת ה-DNS צריך לדעת *איזה* שם מבקשים. פרסור מלא של DNS
 *   מיותר כאן — מספיק לחלץ את שם השאילתה הראשונה (QNAME) ולבנות תשובה
 *   שאומרת "אין דומיין כזה".
 *
 * למה NXDOMAIN ולא הפניה ל-0.0.0.0:
 *   NXDOMAIN גורם לדפדפן להיכשל מיד ובבירור. הפניה ל-0.0.0.0 גורמת לניסיון
 *   חיבור שנתקע עד timeout — חוויה גרועה בהרבה, ובאפליקציות מסוימות זה
 *   נראה כמו תקלת רשת ולא כמו חסימה.
 */
public final class DnsMessage {

    private static final int HEADER_LEN = 12;
    private static final int MAX_NAME_LEN = 253;

    private DnsMessage() {}

    /**
     * מחלץ את שם השאילתה מחבילת DNS. מחזיר null אם זו לא שאילתה תקינה.
     */
    public static String extractQueryName(byte[] dns, int off, int len) {
        try {
            if (len < HEADER_LEN + 5) return null;

            // QR bit (ביט 15 של flags) חייב להיות 0 = שאילתה
            int flags = ((dns[off + 2] & 0xFF) << 8) | (dns[off + 3] & 0xFF);
            if ((flags & 0x8000) != 0) return null;

            int qdCount = ((dns[off + 4] & 0xFF) << 8) | (dns[off + 5] & 0xFF);
            if (qdCount < 1) return null;

            StringBuilder name = new StringBuilder();
            int pos = off + HEADER_LEN;
            int end = off + len;

            while (pos < end) {
                int labelLen = dns[pos] & 0xFF;
                if (labelLen == 0) break;                     // סוף השם
                if ((labelLen & 0xC0) != 0) return null;      // דחיסה — לא צפויה בשאילתה
                pos++;
                if (pos + labelLen > end) return null;
                if (name.length() > 0) name.append('.');
                if (name.length() + labelLen > MAX_NAME_LEN) return null;
                name.append(new String(dns, pos, labelLen, "US-ASCII"));
                pos += labelLen;
            }

            String result = name.toString().toLowerCase();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * בונה תשובת NXDOMAIN לשאילתה נתונה.
     * שומר את ה-ID ואת קטע השאלה (חובה — אחרת ה-resolver מתעלם מהתשובה).
     */
    public static byte[] buildNxDomain(byte[] query, int off, int len) {
        return buildEmptyResponse(query, off, len, 3);   // RCODE 3 = NXDOMAIN
    }

    /**
     * בונה תשובת NODATA (RCODE 0, אפס answers).
     *
     * שימוש עיקרי — חסימת ECH: שאילתה מסוג HTTPS/SVCB (type 65) נושאת את
     * ה-ECHConfig שמצפין את ה-SNI. אם עונים NODATA, הדפדפן לא מקבל את ה-RR,
     * לא משתמש ב-ECH, ונופל חזרה ל-ClientHello עם SNI גלוי — שאותו אנחנו
     * מסננים. NXDOMAIN היה שגוי כאן (הדומיין קיים), NODATA הוא הנכון.
     */
    public static byte[] buildNoData(byte[] query, int off, int len) {
        return buildEmptyResponse(query, off, len, 0);
    }

    private static byte[] buildEmptyResponse(byte[] query, int off, int len, int rcode) {
        try {
            if (len < HEADER_LEN) return null;

            int questionEnd = findQuestionEnd(query, off, len);
            if (questionEnd < 0) return null;

            int responseLen = questionEnd - off;
            byte[] resp = new byte[responseLen];
            System.arraycopy(query, off, resp, 0, responseLen);

            int origFlags = ((query[off + 2] & 0xFF) << 8) | (query[off + 3] & 0xFF);
            int opcode = (origFlags >> 11) & 0x0F;
            int rd     = (origFlags >> 8) & 0x01;
            int newFlags = 0x8000 | (opcode << 11) | (rd << 8) | 0x0080 | (rcode & 0x0F);
            Ipv4.writeShort(resp, 2, newFlags);

            Ipv4.writeShort(resp, 6, 0);   // ANCOUNT
            Ipv4.writeShort(resp, 8, 0);   // NSCOUNT
            Ipv4.writeShort(resp, 10, 0);  // ARCOUNT

            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    /** סוג השאילתה הראשונה (QTYPE). 1=A, 28=AAAA, 65=HTTPS. -1 אם לא ידוע. */
    public static int queryType(byte[] dns, int off, int len) {
        int end = findQuestionEnd(dns, off, len);
        if (end < 0) return -1;
        return ((dns[end - 4] & 0xFF) << 8) | (dns[end - 3] & 0xFF);
    }

    /**
     * בונה תשובת DNS עם רשומת A אחת (מפנה את השם ל-IPv4 נתון).
     * משמש את SafeSearchEnforcer: מפנה את google/youtube ל-IP של גרסת
     * ה-safe search במקום ל-IP הרגיל.
     *
     * מבנה התשובה: כותרת + קטע השאלה המקורי + רשומת answer אחת שמשתמשת
     * בדחיסת שם (pointer ל-offset 12, תחילת השאלה).
     */
    public static byte[] buildAResponse(byte[] query, int off, int len, byte[] ip4) {
        try {
            if (len < HEADER_LEN || ip4 == null || ip4.length != 4) return null;

            // רק אם זו שאילתת A (QTYPE=1) — אחרת עדיף לא לזייף
            int questionEnd = findQuestionEnd(query, off, len);
            if (questionEnd < 0) return null;
            int qtype = ((query[questionEnd - 4] & 0xFF) << 8) | (query[questionEnd - 3] & 0xFF);
            if (qtype != 1) return null;

            int questionLen = questionEnd - off;
            int answerLen = 16;   // ptr(2)+type(2)+class(2)+ttl(4)+rdlen(2)+rdata(4)
            byte[] resp = new byte[questionLen + answerLen];
            System.arraycopy(query, off, resp, 0, questionLen);

            // flags: תשובה, RA=1, RCODE=0
            int origFlags = ((query[off + 2] & 0xFF) << 8) | (query[off + 3] & 0xFF);
            int opcode = (origFlags >> 11) & 0x0F;
            int rd     = (origFlags >> 8) & 0x01;
            int newFlags = 0x8000 | (opcode << 11) | (rd << 8) | 0x0080;
            Ipv4.writeShort(resp, 2, newFlags);
            Ipv4.writeShort(resp, 6, 1);   // ANCOUNT = 1
            Ipv4.writeShort(resp, 8, 0);
            Ipv4.writeShort(resp, 10, 0);

            int p = questionLen;
            resp[p]     = (byte) 0xC0;     // pointer
            resp[p + 1] = (byte) 0x0C;     // ל-offset 12 (השם בשאלה)
            Ipv4.writeShort(resp, p + 2, 1);    // TYPE A
            Ipv4.writeShort(resp, p + 4, 1);    // CLASS IN
            Ipv4.writeInt(resp, p + 6, 300);    // TTL 5 דק'
            Ipv4.writeShort(resp, p + 10, 4);   // RDLENGTH
            resp[p + 12] = ip4[0];
            resp[p + 13] = ip4[1];
            resp[p + 14] = ip4[2];
            resp[p + 15] = ip4[3];

            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    /** מחזיר את ההיסט שאחרי קטע השאלה (QNAME + QTYPE + QCLASS), או -1. */
    private static int findQuestionEnd(byte[] dns, int off, int len) {
        int pos = off + HEADER_LEN;
        int end = off + len;

        while (pos < end) {
            int labelLen = dns[pos] & 0xFF;
            if (labelLen == 0) {
                pos++;                       // אפס סוגר את השם
                if (pos + 4 > end) return -1;
                return pos + 4;              // + QTYPE(2) + QCLASS(2)
            }
            if ((labelLen & 0xC0) != 0) return -1;
            pos += 1 + labelLen;
        }
        return -1;
    }
}
