package com.spatiallauncher.app.ui;

import android.util.Log;

import org.json.JSONArray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Online Google Translate via the public web {@code client=gtx} endpoint
 * (Scenario A style: same service as translate.google.com, not the deprecated widget).
 * Chunks are stable paragraph/sentence units so polish can stay 1:1 with Google output.
 */
final class GoogleWebTranslate {
    private static final String TAG = "GoogleWebTr";
    /** Keep Google + polish on the same unit size (1.5B-safe). */
    private static final int CHUNK_CHARS = 320;

    interface Progress {
        void onChunk(int done, int total);
    }

    /** Translate each source unit; returned list aligns 1:1 with {@link #chunkSource}. */
    static List<String> translateUnits(String article, Progress progress) throws Exception {
        List<String> chunks = chunkSource(article);
        ArrayList<String> out = new ArrayList<>(chunks.size());
        if (chunks.isEmpty()) {
            return out;
        }
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            String piece = cleanOutput(translateChunk(chunks.get(i)));
            out.add(piece == null ? "" : piece);
            if (progress != null) {
                progress.onChunk(i + 1, total);
            }
        }
        return out;
    }

    static String translateArticle(String article, Progress progress) throws Exception {
        return joinUnits(translateUnits(article, progress));
    }

    static String joinUnits(List<String> units) {
        if (units == null || units.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String piece : units) {
            if (piece == null || piece.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(piece.trim());
        }
        return out.toString().trim();
    }

    static String translateChunk(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String q = URLEncoder.encode(text.trim(), StandardCharsets.UTF_8.name());
                String url = "https://translate.googleapis.com/translate_a/single"
                        + "?client=gtx&sl=auto&tl=en&dt=t&q=" + q;
                String body = OptionalHttp.getUtf8(url, 60_000);
                return parseGtx(body);
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "gtx attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(400L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("Google Translate failed");
    }

    static String parseGtx(String body) throws Exception {
        if (body == null || body.isEmpty() || body.charAt(0) != '[') {
            throw new IllegalStateException("unexpected Google response");
        }
        JSONArray root = new JSONArray(body);
        JSONArray sentences = root.optJSONArray(0);
        if (sentences == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.length(); i++) {
            JSONArray part = sentences.optJSONArray(i);
            if (part == null) {
                continue;
            }
            String bit = part.optString(0, "");
            if (!bit.isEmpty()) {
                sb.append(bit);
            }
        }
        String en = sb.toString().trim();
        Log.i(TAG, "gtx out " + en.length() + " chars");
        return en;
    }

    /**
     * Turn Google/Qwen escape junk into real prose. Literal {@code \n} must not
     * reach the WebView as visible characters.
     */
    static String cleanOutput(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String t = raw;
        // Literal escape sequences (backslash + letter) → space / break.
        t = t.replace("\\r\\n", "\n");
        t = t.replace("\\n", "\n");
        t = t.replace("\\r", "\n");
        t = t.replace("\\t", " ");
        // Real control chars from JSON → paragraph-friendly newlines.
        t = t.replace('\r', '\n');
        t = t.replace('\t', ' ');
        // Stray backslashes left over.
        t = t.replace("\\", "");
        // Collapse whitespace / blank lines.
        t = t.replaceAll("[ \\u00A0\\u3000]+", " ");
        t = t.replaceAll(" *\\n *", "\n");
        t = t.replaceAll("\\n{3,}", "\n\n");
        t = t.replaceAll("(?i)n{2,}n't", "n't");
        t = t.replaceAll("/{2,}", " ");
        // Qwen often echoes the prompt label / instructions into the page.
        t = t.replaceAll("(?im)^\\s*Corrected:\\s*", "");
        t = t.replaceAll("(?i)\\bCorrected:\\s*", "");
        t = stripPromptLeak(t);
        return t.trim();
    }

    /** True if the blob is mostly model/instruction leakage rather than story text. */
    static boolean looksLikePromptLeak(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("rewrite this")
                || lower.contains("keep the same length")
                || lower.contains("output only the corrected")
                || lower.contains("correcting light-novel")
                || lower.contains("hope you enjoy it")
                || lower.contains("previous polished english");
    }

    static String stripPromptLeak(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String L = line.trim();
            if (L.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                continue;
            }
            String lower = L.toLowerCase();
            if (lower.startsWith("corrected:")
                    || lower.startsWith("corrected ")
                    || lower.contains("rewrite this english")
                    || lower.contains("keep the same length")
                    || lower.contains("output only the corrected")
                    || lower.contains("correcting light-novel")
                    || lower.contains("hope you enjoy it")
                    || lower.contains("previous polished english")
                    || lower.startsWith("current english:")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    /**
     * Paragraph-first units capped at {@link #CHUNK_CHARS} so Google and polish
     * share the same boundaries.
     */
    static List<String> chunkSource(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        String[] paras = text.trim().split("\\n+");
        for (String para : paras) {
            String line = para.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() <= CHUNK_CHARS) {
                out.add(line);
                continue;
            }
            // Long paragraph: split on JP/EN sentence ends, pack to CHUNK_CHARS.
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < line.length(); ) {
                int nextBreak = -1;
                for (int j = i; j < line.length(); j++) {
                    char c = line.charAt(j);
                    boolean end = c == '。' || c == '！' || c == '？' || c == '…'
                            || c == '.' || c == '!' || c == '?';
                    if (end) {
                        nextBreak = j + 1;
                        break;
                    }
                    if (j - i >= CHUNK_CHARS) {
                        nextBreak = j;
                        break;
                    }
                }
                if (nextBreak < 0) {
                    String rest = line.substring(i).trim();
                    if (!rest.isEmpty()) {
                        if (buf.length() > 0 && buf.length() + rest.length() + 1 > CHUNK_CHARS) {
                            out.add(buf.toString().trim());
                            buf.setLength(0);
                        }
                        if (buf.length() > 0) {
                            buf.append(' ');
                        }
                        buf.append(rest);
                    }
                    break;
                }
                String sent = line.substring(i, nextBreak).trim();
                i = nextBreak;
                if (sent.isEmpty()) {
                    continue;
                }
                if (buf.length() > 0 && buf.length() + sent.length() + 1 > CHUNK_CHARS) {
                    out.add(buf.toString().trim());
                    buf.setLength(0);
                }
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(sent);
            }
            if (buf.length() > 0) {
                out.add(buf.toString().trim());
            }
        }
        return out;
    }

    private GoogleWebTranslate() {
    }
}
