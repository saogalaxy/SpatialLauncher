package com.spatiallauncher.app.ui;

import java.util.HashSet;
import java.util.Locale;

/**
 * Avoiding repeating the same text (deduplication).
 * <p>
 * If you capture frames at 5 FPS, the dialogue box will show the same text for
 * several seconds. Solution: keep a hash of the last spoken line and a
 * Levenshtein check. Only treat text as new (and invoke {@code tts.speak}) when
 * it differs significantly from the previous output.
 */
final class DialogueDeduper {
    /** Fraction of characters that must change (0–1) to count as a new line. */
    static final float SIGNIFICANT_CHANGE_RATIO = 0.40f;
    /** Shared-word Jaccard above this ⇒ same subtitle (OCR flicker / outline font). */
    static final float SAME_LINE_WORD_OVERLAP = 0.55f;

    private String lastNormalized = "";
    private int lastHash;

    boolean isNew(String raw) {
        String normalized = normalize(raw);
        if (!wouldBeNewNormalized(normalized)) {
            return false;
        }
        lastNormalized = normalized;
        lastHash = normalized.hashCode();
        return true;
    }

    /** True if this line would pass the hash/Levenshtein gate without committing. */
    boolean wouldBeNew(String raw) {
        return wouldBeNewNormalized(normalize(raw));
    }

    private boolean wouldBeNewNormalized(String normalized) {
        if (normalized.isEmpty()) {
            return false;
        }
        int hash = normalized.hashCode();
        if (!lastNormalized.isEmpty() && hash == lastHash && normalized.equals(lastNormalized)) {
            return false;
        }
        if (!lastNormalized.isEmpty() && !differsSignificantly(lastNormalized, normalized)) {
            return false;
        }
        return true;
    }

    /**
     * True when the next caption is a different spoken line, not OCR flicker of
     * the same subtitle (punctuation, outline-font misreads, extra speaker name).
     */
    static boolean differsSignificantly(String previous, String next) {
        if (previous == null || previous.isEmpty()) {
            return next != null && !next.isEmpty();
        }
        if (next == null || next.isEmpty()) {
            return false;
        }
        if (previous.equals(next)) {
            return false;
        }
        // Substring / containment: OCR often drops a speaker name or one word.
        // Truncation flicker (full → short) stays the same line. Extension
        // (partial → fuller subtitle) must count as new or speech cuts mid-line.
        if (previous.contains(next) || next.contains(previous)) {
            if (next.length() > previous.length() + 2) {
                return true;
            }
            return false;
        }
        if (wordOverlap(previous, next) >= SAME_LINE_WORD_OVERLAP) {
            return false;
        }
        if (coverage(previous, next) >= 0.75f || coverage(next, previous) >= 0.75f) {
            return false;
        }
        int maxLen = Math.max(previous.length(), next.length());
        int distance = levenshtein(previous, next);
        return distance / (float) maxLen >= SIGNIFICANT_CHANGE_RATIO;
    }

    /** Fraction of words in {@code needle} that also appear in {@code haystack}. */
    static float coverage(String haystack, String needle) {
        String[] words = needle.split(" ");
        if (words.length == 0 || words[0].isEmpty()) {
            return 0f;
        }
        HashSet<String> hay = new HashSet<>();
        for (String w : haystack.split(" ")) {
            if (w.length() > 1) {
                hay.add(w);
            }
        }
        if (hay.isEmpty()) {
            return 0f;
        }
        int hit = 0;
        int total = 0;
        for (String w : words) {
            if (w.length() <= 1) {
                continue;
            }
            total++;
            if (hay.contains(w)) {
                hit++;
            }
        }
        return total == 0 ? 0f : hit / (float) total;
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String lowered = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        return lowered.replaceAll("\\s+", " ");
    }

    /** Fraction of words shared (Jaccard). High overlap ⇒ same subtitle, noisy OCR. */
    static float wordOverlap(String previous, String next) {
        String[] a = previous.split(" ");
        String[] b = next.split(" ");
        if (a.length == 0 || b.length == 0 || a[0].isEmpty() || b[0].isEmpty()) {
            return 0f;
        }
        HashSet<String> sa = new HashSet<>();
        HashSet<String> sb = new HashSet<>();
        for (String w : a) {
            if (w.length() > 1) {
                sa.add(w);
            }
        }
        for (String w : b) {
            if (w.length() > 1) {
                sb.add(w);
            }
        }
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0f;
        }
        int inter = 0;
        for (String w : sa) {
            if (sb.contains(w)) {
                inter++;
            }
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0f : inter / (float) union;
    }

    static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }
}
