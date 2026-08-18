package com.magen.family.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Aho-Corasick — חיפוש רב-מילים על מחרוזת ב-O(n + m) במקום O(n × m).
 *
 * הגרסה הקודמת בצעה: for each word in 200 → text.contains(word).
 * זה O(text × words) = איטי מאוד על TikTok scroll עם 200 מילים ו-1000 nodes.
 *
 * שתי תוספות בגרסה זו:
 *
 * 1. בדיקת גבולות מילה בתוך המעבר עצמו.
 *    בלעדיה, חיפוש תת-מחרוזת טהור גרם לחסימות שווא המוניות:
 *      document      מכיל "cum"      analysis  מכיל "anal"
 *      Essex         מכיל "sex"      vacuum    מכיל "cum"
 *      circumstances מכיל "cum"      stripe    מכיל "strip"
 *    כל מסך באינסטגרם/יוטיוב עם המילה "documents" גרר HOME כפוי ונעילת צינון.
 *    הבדיקה נעשית תוך כדי הסריקה כדי לשמור על O(n).
 *
 * 2. build() אידמפוטנטי — ה-trie נבנה מחדש מרשימת הדפוסים המקורית.
 *    בגרסה הקודמת קריאה חוזרת ל-build() הכפילה את רשימות ההתאמות
 *    (child.matches.addAll(child.fail.matches) הצטבר).
 */
public class AhoCorasick {

    /** מילה שאורכה מעל הסף נחשבת חד-משמעית ולא נדרשת לגבולות מילה. */
    private static final int LONG_WORD_LEN = 6;

    private static class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        /** הדפוסים שמסתיימים כאן, כולל אלו שהתקבלו בירושה משרשרת ה-fail. */
        final List<String> matches = new ArrayList<>();
    }

    /** מקור האמת — ה-trie נבנה מזה בכל build(). */
    private final Set<String> patterns = new LinkedHashSet<>();

    private Node root = new Node();
    private volatile boolean built = false;

    public void addPattern(String pattern) {
        if (pattern == null) return;
        String p = pattern.trim();
        if (p.isEmpty()) return;
        patterns.add(p);
        built = false;
    }

    public void addAll(Collection<String> newPatterns) {
        if (newPatterns == null) return;
        for (String p : newPatterns) addPattern(p);
    }

    public int size() {
        return patterns.size();
    }

    /** בונה את ה-automaton מאפס. בטוח לקריאה חוזרת. */
    public synchronized void build() {
        Node newRoot = new Node();

        // 1. trie
        for (String p : patterns) {
            Node cur = newRoot;
            for (char c : p.toLowerCase().toCharArray()) {
                cur = cur.children.computeIfAbsent(c, k -> new Node());
            }
            cur.matches.add(p);
        }

        // 2. fail links (BFS) + ירושת התאמות
        Queue<Node> queue = new LinkedList<>();
        for (Node child : newRoot.children.values()) {
            child.fail = newRoot;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            for (Map.Entry<Character, Node> e : cur.children.entrySet()) {
                char c = e.getKey();
                Node child = e.getValue();
                Node fail = cur.fail;
                while (fail != null && !fail.children.containsKey(c)) fail = fail.fail;
                child.fail = (fail == null) ? newRoot : fail.children.get(c);
                if (child.fail != null && child.fail != child) {
                    child.matches.addAll(child.fail.matches);
                }
                queue.add(child);
            }
        }

        root = newRoot;
        built = true;
    }

    /**
     * מחזיר את המילה האסורה הראשונה שנמצאה, או null.
     *
     * @param requireWordBoundary אם true — התאמה קצרה (עד LONG_WORD_LEN תווים)
     *                            מתקבלת רק אם התו שלפניה ואחריה אינם אות/ספרה.
     *                            כך "sex" תופס "sex tape" אבל לא "Essex".
     */
    public String findFirst(String text, boolean requireWordBoundary) {
        if (!built) build();
        if (text == null || text.isEmpty()) return null;

        String lower = text.toLowerCase();
        Node cur = root;

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            while (cur != root && !cur.children.containsKey(c)) cur = cur.fail;
            Node next = cur.children.get(c);
            cur = (next == null) ? root : next;

            if (cur.matches.isEmpty()) continue;

            // i = האינדקס של התו האחרון בהתאמה
            for (String hit : cur.matches) {
                int start = i - hit.length() + 1;
                if (!requireWordBoundary || isWholeWord(lower, start, hit.length())) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** ברירת מחדל — עם בדיקת גבולות מילה. לשימוש על טקסט חופשי. */
    public String findFirst(String text) {
        return findFirst(text, true);
    }

    public boolean contains(String text) {
        return findFirst(text, true) != null;
    }

    /**
     * חיפוש תת-מחרוזת גולמי, בלי גבולות מילה.
     * לשימוש על דומיינים/URL, שם "freeporn.com" חייב להיתפס למרות שאין רווח.
     */
    public boolean containsRaw(String text) {
        return findFirst(text, false) != null;
    }

    // ---------------- גבולות מילה ----------------

    private static boolean isWholeWord(String text, int start, int len) {
        if (start < 0) return false;
        if (len > LONG_WORD_LEN) return true;   // מילה ארוכה — חד-משמעית

        int end = start + len;
        if (start > 0 && isWordChar(text.charAt(start - 1))) return false;
        if (end < text.length() && isWordChar(text.charAt(end))) return false;
        return true;
    }

    /** Character.isLetterOrDigit מטפל נכון גם בעברית. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
