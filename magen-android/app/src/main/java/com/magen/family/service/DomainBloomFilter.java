package com.magen.family.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * DomainBloomFilter — מחזיק מיליוני דומיינים חסומים בזיכרון קטן מאוד.
 *
 * למה Bloom filter ולא HashSet רגיל?
 *   HashSet של 2-3 מיליון דומיינים = עשרות-מאות MB בזיכרון. הרבה מדי לטלפון.
 *   Bloom filter מחזיק את אותו מספר דומיינים ב-~5-10MB, עם בדיקה ב-O(1).
 *
 * איך זה עובד:
 *   כל דומיין עובר k פונקציות hash שמדליקות k ביטים במערך.
 *   בבדיקה: אם כל k הביטים דלוקים -> "כנראה חסום". אם ביט אחד כבוי -> "בטוח לא חסום".
 *
 * תכונה חשובה: יש אחוז קטן של false-positives (דומיין תמים שמסומן כחסום),
 *   אבל *אף פעם* לא false-negative (דומיין חסום לא יחמוק). למסנן כשר זה
 *   הכיוון הנכון — עדיף לחסום בטעות מדי פעם מאשר לפספס.
 *   עם הפרמטרים כאן: ~1% false-positive ל-2M דומיינים.
 *
 * תומך בשמירה/טעינה מקובץ כדי לא לבנות מחדש בכל הפעלה.
 */
public class DomainBloomFilter {

    private static final int MAGIC = 0x4D42_4C4D;   // "MBLM"
    private final BitSet bits;
    private final int size;          // מספר ביטים
    private final int hashCount;     // k
    private int itemCount = 0;

    /**
     * @param expectedItems  כמה דומיינים צפויים (למשל 3_000_000)
     * @param falsePositive  יעד false-positive (למשל 0.01 = 1%)
     */
    public DomainBloomFilter(int expectedItems, double falsePositive) {
        // נוסחאות סטנדרטיות לגודל אופטימלי
        this.size = optimalSize(expectedItems, falsePositive);
        this.hashCount = optimalHashCount(expectedItems, size);
        this.bits = new BitSet(size);
    }

    private DomainBloomFilter(int size, int hashCount, int itemCount, BitSet bits) {
        this.size = size;
        this.hashCount = hashCount;
        this.itemCount = itemCount;
        this.bits = bits;
    }

    private static int optimalSize(int n, double p) {
        int m = (int) Math.ceil((-n * Math.log(p)) / (Math.log(2) * Math.log(2)));
        return Math.max(m, 1024);
    }

    private static int optimalHashCount(int n, int m) {
        int k = (int) Math.round((double) m / n * Math.log(2));
        return Math.max(1, Math.min(k, 12));
    }

    // ---------------- הוספה ובדיקה ----------------

    public void add(String domain) {
        if (domain == null) return;
        domain = domain.toLowerCase().trim();
        if (domain.isEmpty()) return;
        long[] h = hashes(domain);
        for (int i = 0; i < hashCount; i++) {
            int idx = (int) ((h[0] + (long) i * h[1]) % size);
            if (idx < 0) idx += size;
            bits.set(idx);
        }
        itemCount++;
    }

    /** בדיקה ישירה של דומיין מדויק. */
    public boolean mightContain(String domain) {
        if (domain == null) return false;
        domain = domain.toLowerCase().trim();
        if (domain.isEmpty()) return false;
        long[] h = hashes(domain);
        for (int i = 0; i < hashCount; i++) {
            int idx = (int) ((h[0] + (long) i * h[1]) % size);
            if (idx < 0) idx += size;
            if (!bits.get(idx)) return false;   // ביט כבוי -> בטוח לא ברשימה
        }
        return true;
    }

    /**
     * בדיקה חכמה שכוללת סאב-דומיינים:
     * אם host = "sub.bad.com" ו-"bad.com" ברשימה — יחזיר true.
     */
    public boolean isBlockedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase().trim();
        if (host.startsWith("www.")) host = host.substring(4);
        if (mightContain(host)) return true;
        int dot = host.indexOf('.');
        while (dot >= 0 && dot < host.length() - 1) {
            String parent = host.substring(dot + 1);
            if (parent.indexOf('.') < 0) break;   // הגענו ל-TLD, עצור
            if (mightContain(parent)) return true;
            dot = host.indexOf('.', dot + 1);
        }
        return false;
    }

    public int getItemCount() { return itemCount; }

    // ---------------- hash (FNV-1a כפול, double hashing) ----------------

    private long[] hashes(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        long h1 = 0xcbf29ce484222325L;
        long h2 = 0x100000001b3L;
        for (byte b : data) {
            h1 ^= (b & 0xff);
            h1 *= 0x100000001b3L;
            h2 = (h2 ^ (b & 0xff)) * 0xcbf29ce484222325L;
        }
        // & Long.MAX_VALUE מבטיח אי-שליליות (Math.abs(Long.MIN_VALUE) נשאר שלילי!)
        return new long[]{ (h1 & Long.MAX_VALUE) % size, ((h2 & Long.MAX_VALUE) % (size - 1)) + 1 };
    }

    // ---------------- שמירה/טעינה מקובץ ----------------

    public void writeTo(OutputStream os) throws IOException {
        DataOutputStream d = new DataOutputStream(os);
        d.writeInt(MAGIC);
        d.writeInt(size);
        d.writeInt(hashCount);
        d.writeInt(itemCount);
        long[] words = bits.toLongArray();
        d.writeInt(words.length);
        for (long w : words) d.writeLong(w);
        d.flush();
    }

    public static DomainBloomFilter readFrom(InputStream is) throws IOException {
        DataInputStream d = new DataInputStream(is);
        if (d.readInt() != MAGIC) throw new IOException("bad bloom file");
        int size = d.readInt();
        int hashCount = d.readInt();
        int itemCount = d.readInt();
        int wordLen = d.readInt();
        long[] words = new long[wordLen];
        for (int i = 0; i < wordLen; i++) words[i] = d.readLong();
        return new DomainBloomFilter(size, hashCount, itemCount, BitSet.valueOf(words));
    }
}
