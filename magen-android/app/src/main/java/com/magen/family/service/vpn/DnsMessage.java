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
        try {
            if (len < HEADER_LEN) return null;

            int questionEnd = findQuestionEnd(query, off, len);
            if (questionEnd < 0) return null;

            int responseLen = questionEnd - off;
            byte[] resp = new byte[responseLen];
            System.arraycopy(query, off, resp, 0, responseLen);

            // flags: QR=1 (תשובה), Opcode נשמר, AA=0, TC=0, RD נשמר,
            //        RA=1 (רקורסיה זמינה), RCODE=3 (NXDOMAIN)
            int origFlags = ((query[off + 2] & 0xFF) << 8) | (query[off + 3] & 0xFF);
            int opcode = (origFlags >> 11) & 0x0F;
            int rd     = (origFlags >> 8) & 0x01;
            int newFlags = 0x8000 | (opcode << 11) | (rd << 8) | 0x0080 | 0x0003;
            Ipv4.writeShort(resp, 2, newFlags);

            // QDCOUNT נשאר, שאר המונים מתאפסים
            Ipv4.writeShort(resp, 6, 0);   // ANCOUNT
            Ipv4.writeShort(resp, 8, 0);   // NSCOUNT
            Ipv4.writeShort(resp, 10, 0);  // ARCOUNT

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
