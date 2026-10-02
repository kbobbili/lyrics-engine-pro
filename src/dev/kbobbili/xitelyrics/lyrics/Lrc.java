package dev.kbobbili.xitelyrics.lyrics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC parsing and text normalisation (port of tools/phase2/lyrics.py). */
public final class Lrc {
    private Lrc() {}

    public static final class Line {
        /** Seconds on the recording's timeline. */
        public final double t;
        /** "" marks a deliberate gap (instrumental/blank line). */
        public final String text;

        Line(double t, String text) {
            this.t = t;
            this.text = text;
        }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("\\[[^\\]]*\\]");

    /** Handles fractional and repeated timestamps, [offset:ms] (positive = earlier) and blank lines. */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        double offsetS = 0;
        Matcher om = OFFSET.matcher(lrc);
        if (om.find()) offsetS = Integer.parseInt(om.group(1)) / 1000.0;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Double> stamps = new ArrayList<>();
            while (m.find()) stamps.add(Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2)));
            if (stamps.isEmpty()) continue;
            String text = TAG.matcher(raw).replaceAll("").trim();
            for (double t : stamps) out.add(new Line(t - offsetS, text));
        }
        Collections.sort(out, (a, b) -> Double.compare(a.t, b.t));
        return out;
    }

    /** Index of the line active at time t (seconds), or -1 before the first line. */
    public static int lineAt(List<Line> lines, double t) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).t <= t) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** Lowercase ASCII words: drops accents, (...)/[...] and "feat." tails. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT);
        n = n.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");
        n = n.replaceAll("\\bfeat\\.?.*$|\\bft\\..*$", " ");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
