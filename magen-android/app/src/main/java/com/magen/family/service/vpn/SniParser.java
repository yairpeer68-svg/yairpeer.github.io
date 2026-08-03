package com.magen.family.service.vpn;

/**
 * SniParser — חילוץ שם המארח מתוך תחילת חיבור TCP.
 *
 * למה זה הרכיב הכי חשוב בסינון:
 *   סינון DNS נשבר ברגע שמישהו משתמש ב-resolver אחר, ב-DoH, או פשוט ניגש
 *   ישירות ל-IP. סינון SNI קורא את שם הדומיין *מתוך החיבור עצמו* — בשדה
 *   Server Name Indication שנשלח בגלוי (unencrypted) בהודעת ClientHello
 *   הראשונה של כל חיבור TLS.
 *
 *   זה עובד גם כשה-DNS נעקף לגמרי, וזה לא דורש Root CA ולא שובר
 *   certificate pinning — אנחנו רק *קוראים* את השם ומחליטים אם להמשיך.
 *
 * מגבלה עתידית שכדאי להכיר:
 *   ECH (Encrypted Client Hello) מצפין גם את ה-SNI. הוא עדיין לא נפוץ,
 *   וכשהוא כן — הוא דורש רשומת DNS מסוג HTTPS שאנחנו שולטים בה דרך
 *   סינון ה-DNS, כך שאפשר יהיה לחסום אותו.
 */
public final class SniParser {

    private SniParser() {}

    /**
     * מנסה לחלץ שם מארח מ-buffer של תחילת חיבור.
     * תומך גם ב-TLS ClientHello (443) וגם בכותרת Host של HTTP (80).
     * מחזיר null אם עוד אין מספיק בייטים או שלא נמצא שם.
     */
    public static String extractHost(byte[] buf, int len) {
        String sni = extractTlsSni(buf, len);
        if (sni != null) return sni;
        return extractHttpHost(buf, len);
    }

    /**
     * האם בכלל יש סיכוי שבזרם הזה יופיע שם מארח?
     *
     * קריטי למניעת תקיעות: אם היינו מחזיקים כל חיבור עד שיימלא באגר הבדיקה,
     * פרוטוקול שאינו TLS/HTTP ששולח מעט בייטים וממתין לתשובת השרת היה נתקע
     * לנצח — אנחנו מחכים לעוד נתונים, והוא מחכה לנו. לכן מזהים מיד שאין טעם
     * להמשיך להצטבר ומשחררים את החיבור.
     */
    public static boolean mayContainHost(byte[] buf, int len) {
        if (len < 1) return true;                     // מוקדם מדי להכריע
        if ((buf[0] & 0xFF) == 0x16) return true;     // TLS handshake
        if (len < 8) return true;
        try {
            return looksLikeHttp(new String(buf, 0, 8, "US-ASCII"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * האם רשומת ה-TLS הראשונה התקבלה במלואה?
     * אם כן ולא נמצא SNI — אין טעם להמשיך לחכות.
     */
    public static boolean isTlsRecordComplete(byte[] buf, int len) {
        if (len < 5) return false;
        if ((buf[0] & 0xFF) != 0x16) return false;
        int recordLen = u16(buf, 3);
        return len >= recordLen + 5;
    }

    // ---------------- TLS ----------------

    /**
     * מבנה ClientHello:
     *   [0]      content type = 0x16 (handshake)
     *   [1..2]   version
     *   [3..4]   record length
     *   [5]      handshake type = 0x01 (client hello)
     *   [6..8]   handshake length
     *   [9..10]  client version
     *   [11..42] random (32 בייט)
     *   [43]     session id length,  ואז session id
     *            cipher suites length (2), ואז suites
     *            compression methods length (1), ואז methods
     *            extensions length (2), ואז רשימת extensions
     *   extension type 0x0000 = server_name
     */
    public static String extractTlsSni(byte[] buf, int len) {
        try {
            if (len < 45) return null;
            if ((buf[0] & 0xFF) != 0x16) return null;    // לא handshake
            if ((buf[5] & 0xFF) != 0x01) return null;    // לא ClientHello

            int pos = 43;                                 // אחרי random

            int sessionIdLen = buf[pos] & 0xFF;
            pos += 1 + sessionIdLen;
            if (pos + 2 > len) return null;

            int cipherSuitesLen = u16(buf, pos);
            pos += 2 + cipherSuitesLen;
            if (pos + 1 > len) return null;

            int compressionLen = buf[pos] & 0xFF;
            pos += 1 + compressionLen;
            if (pos + 2 > len) return null;

            int extensionsLen = u16(buf, pos);
            pos += 2;
            int extensionsEnd = Math.min(pos + extensionsLen, len);

            while (pos + 4 <= extensionsEnd) {
                int extType = u16(buf, pos);
                int extLen  = u16(buf, pos + 2);
                pos += 4;
                if (pos + extLen > extensionsEnd) return null;

                if (extType == 0x0000) {                  // server_name
                    return parseServerNameExtension(buf, pos, extLen);
                }
                pos += extLen;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * מבנה server_name extension:
     *   [0..1] server name list length
     *   [2]    name type (0 = host_name)
     *   [3..4] name length
     *   [5..]  ה-hostname עצמו
     */
    private static String parseServerNameExtension(byte[] buf, int off, int len) {
        if (len < 5) return null;
        int pos = off + 2;                                // דילוג על list length
        int nameType = buf[pos] & 0xFF;
        if (nameType != 0) return null;                   // רק host_name
        pos++;
        int nameLen = u16(buf, pos);
        pos += 2;
        if (nameLen <= 0 || pos + nameLen > off + len) return null;

        try {
            return new String(buf, pos, nameLen, "US-ASCII").toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- HTTP ----------------

    /** מחפש "Host:" בכותרות HTTP בטקסט רגיל. */
    public static String extractHttpHost(byte[] buf, int len) {
        try {
            if (len < 16) return null;
            // רק אם זה נראה כמו בקשת HTTP
            String head = new String(buf, 0, Math.min(len, 8), "US-ASCII");
            if (!looksLikeHttp(head)) return null;

            String text = new String(buf, 0, Math.min(len, 2048), "US-ASCII");
            int idx = indexOfIgnoreCase(text, "\r\nhost:");
            if (idx < 0) return null;

            int start = idx + 7;
            int end = text.indexOf("\r\n", start);
            if (end < 0) return null;

            String host = text.substring(start, end).trim().toLowerCase();
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);
            return host.isEmpty() ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeHttp(String head) {
        return head.startsWith("GET ") || head.startsWith("POST ")
            || head.startsWith("HEAD ") || head.startsWith("PUT ")
            || head.startsWith("DELETE ") || head.startsWith("OPTIONS")
            || head.startsWith("CONNECT") || head.startsWith("PATCH ");
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }
}
