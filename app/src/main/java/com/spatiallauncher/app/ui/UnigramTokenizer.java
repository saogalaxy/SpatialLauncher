package com.spatiallauncher.app.ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SentencePiece Unigram encode/decode from a pieces.tsv built at compile time. */
final class UnigramTokenizer {
    private static final double NEG = -1e9;

    private final String[] pieces;
    private final double[] scores;
    private final Map<String, Integer> index = new HashMap<>();
    private final int unkId;
    private final int eosId;
    private final int padId;

    UnigramTokenizer(File tsv, int unkId, int eosId, int padId) throws Exception {
        this.unkId = unkId;
        this.eosId = eosId;
        this.padId = padId;
        List<String> p = new ArrayList<>();
        List<Double> s = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(tsv), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String piece = unescape(line.substring(0, tab));
                double score = Double.parseDouble(line.substring(tab + 1));
                index.put(piece, p.size());
                p.add(piece);
                s.add(score);
            }
        }
        pieces = p.toArray(new String[0]);
        scores = new double[s.size()];
        for (int i = 0; i < s.size(); i++) {
            scores[i] = s.get(i);
        }
    }

    int[] encode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new int[] {eosId};
        }
        String text = raw.replace(' ', '▁');
        if (!text.startsWith("▁")) {
            text = "▁" + text;
        }
        int n = text.length();
        double[] best = new double[n + 1];
        int[] prev = new int[n + 1];
        int[] tok = new int[n + 1];
        java.util.Arrays.fill(best, NEG);
        best[0] = 0;
        prev[0] = -1;
        for (int i = 0; i < n; i++) {
            if (best[i] <= NEG / 2) {
                continue;
            }
            boolean matched = false;
            int maxLen = Math.min(24, n - i);
            for (int len = maxLen; len >= 1; len--) {
                String slice = text.substring(i, i + len);
                Integer id = index.get(slice);
                if (id == null) {
                    continue;
                }
                matched = true;
                int j = i + len;
                double sc = best[i] + scores[id];
                if (sc > best[j]) {
                    best[j] = sc;
                    prev[j] = i;
                    tok[j] = id;
                }
            }
            if (!matched) {
                int j = i + Character.charCount(text.codePointAt(i));
                double sc = best[i] - 10;
                if (sc > best[j]) {
                    best[j] = sc;
                    prev[j] = i;
                    tok[j] = unkId;
                }
            }
        }
        ArrayList<Integer> ids = new ArrayList<>();
        int cursor = n;
        if (best[n] <= NEG / 2) {
            ids.add(unkId);
        } else {
            while (cursor > 0) {
                ids.add(tok[cursor]);
                cursor = prev[cursor];
            }
            java.util.Collections.reverse(ids);
        }
        ids.add(eosId);
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    String decode(int[] ids) {
        if (ids == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (id == eosId || id == padId || id < 0 || id >= pieces.length) {
                continue;
            }
            String piece = pieces[id];
            if ("<unk>".equals(piece) || "<s>".equals(piece) || "</s>".equals(piece) || "<pad>".equals(piece)) {
                continue;
            }
            sb.append(piece);
        }
        return sb.toString().replace('▁', ' ').replace("  ", " ").trim();
    }

    private static String unescape(String piece) {
        return piece.replace("\\t", "\t").replace("\\\\", "\\");
    }
}
