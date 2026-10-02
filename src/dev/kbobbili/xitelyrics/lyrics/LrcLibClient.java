package dev.kbobbili.xitelyrics.lyrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LRCLIB search and recording selection (port of tools/phase2/lyrics.py).
 *
 * Phase 2 findings baked in:
 * - Group uploads by lyric TIMING, not metadata duration (one recording's
 *   uploads span ~10 s of "duration"); shared lines within 0.75 s = same recording.
 * - Drop snippets (< 60 s or < 5 timed lines); prefer unlabelled over
 *   remix/live/... uploads; pick the largest timing group, ambiguous if the
 *   runner-up is at least half its size.
 * - LRCLIB returns transient 503s and TLS timeouts: retry, and cache results
 *   on disk so a song is fetched once.
 * Blocking; call from a worker thread.
 */
public final class LrcLibClient {
    private static final String[] VERSION_WORDS = {"remix", "live", "acoustic", "sped up", "slowed",
            "instrumental", "karaoke", "demo", "radio edit", "extended", "unplugged", "reverb"};
    private static final double SAME_TIMING_S = 0.75;
    private static final int MIN_COMMON_LINES = 5;
    private static final double MIN_DURATION_S = 60;
    private static final int[] RETRY_WAIT_MS = {2_000, 5_000, 10_000};
    private static final String BASE_URL = "https://lrclib.net";

    public enum Status { OK, AMBIGUOUS, NONE, LOOKUP_FAILED }

    public static final class Choice {
        public final Status status;
        public final long candidateId;
        public final List<Lrc.Line> lines;
        public final String note;
        /** Duration (s) LRCLIB lists for the chosen upload; 0 if none. */
        public double duration;

        Choice(Status status, long candidateId, List<Lrc.Line> lines, String note) {
            this.status = status;
            this.candidateId = candidateId;
            this.lines = lines;
            this.note = note;
        }
    }

    private final File cacheDir;
    private final String userAgent;
    private final String baseUrl;
    private final int[] retryWaitMs;

    public LrcLibClient(File cacheDir, String userAgent) {
        this(cacheDir, userAgent, BASE_URL, RETRY_WAIT_MS);
    }

    /** Test seam: alternative server and retry waits. */
    public LrcLibClient(File cacheDir, String userAgent, String baseUrl, int[] retryWaitMs) {
        this.cacheDir = cacheDir;
        this.userAgent = userAgent;
        this.baseUrl = baseUrl;
        this.retryWaitMs = retryWaitMs;
    }

    public Choice choose(String title, String artist) {
        JSONArray results;
        try {
            results = search(title, artist);
        } catch (IOException | JSONException e) {
            return new Choice(Status.LOOKUP_FAILED, 0, new ArrayList<>(), e.toString());
        }
        return select(results, title, artist);
    }

    /** Pure selection logic over a search result (unit-testable without network). */
    public static Choice select(JSONArray results, String title, String artist) {
        String wantT = Lrc.norm(title), wantA = Lrc.norm(artist);
        List<JSONObject> cands = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject c = results.optJSONObject(i);
            if (c == null) continue;
            String synced = c.optString("syncedLyrics", "");
            if (synced.isEmpty() || "null".equals(synced)) continue;
            if (!Lrc.norm(c.optString("trackName")).equals(wantT)) continue;
            String a = Lrc.norm(c.optString("artistName"));
            if (!(a.contains(wantA) || wantA.contains(a))) continue;
            if (c.optDouble("duration", 0) < MIN_DURATION_S) continue;
            if (Lrc.parse(synced).size() < 5) continue;
            cands.add(c);
        }
        if (cands.isEmpty()) return new Choice(Status.NONE, 0, new ArrayList<>(), "no full-length synced candidate");
        List<JSONObject> plain = new ArrayList<>();
        for (JSONObject c : cands) if (labels(c).isEmpty()) plain.add(c);
        List<JSONObject> pool = plain.isEmpty() ? cands : plain;

        List<Map<String, Double>> keys = new ArrayList<>();
        for (JSONObject c : pool) keys.add(timingKey(c));
        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            List<Integer> home = null;
            for (List<Integer> g : groups) {
                for (int j : g) {
                    if (sameTiming(keys.get(i), keys.get(j))) {
                        home = g;
                        break;
                    }
                }
                if (home != null) break;
            }
            if (home == null) {
                home = new ArrayList<>();
                groups.add(home);
            }
            home.add(i);
        }
        groups.sort((a, b) -> b.size() - a.size());
        if (groups.size() > 1 && groups.get(1).size() * 2 >= groups.get(0).size()) {
            return new Choice(Status.AMBIGUOUS, 0, new ArrayList<>(), groups.size() + " timing groups");
        }
        JSONObject best = bestInGroup(groups.get(0), pool, keys);
        Choice ok = new Choice(Status.OK, best.optLong("id"), Lrc.parse(best.optString("syncedLyrics")), "");
        ok.duration = best.optDouble("duration", 0);
        return ok;
    }

    private static Set<String> labels(JSONObject c) {
        String text = (c.optString("trackName") + " " + c.optString("albumName")).toLowerCase();
        Set<String> out = new HashSet<>();
        for (String w : VERSION_WORDS) if (text.contains(w)) out.add(w);
        return out;
    }

    private static Map<String, Double> timingKey(JSONObject c) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Lrc.Line l : Lrc.parse(c.optString("syncedLyrics"))) {
            String k = Lrc.norm(l.text);
            if (!k.isEmpty() && !out.containsKey(k)) out.put(k, l.t);
        }
        return out;
    }

    private static boolean sameTiming(Map<String, Double> a, Map<String, Double> b) {
        List<Double> diffs = new ArrayList<>();
        for (Map.Entry<String, Double> e : a.entrySet()) {
            Double other = b.get(e.getKey());
            if (other != null) diffs.add(Math.abs(e.getValue() - other));
        }
        return diffs.size() >= MIN_COMMON_LINES && median(diffs) <= SAME_TIMING_S;
    }

    private static JSONObject bestInGroup(List<Integer> group, List<JSONObject> pool, List<Map<String, Double>> keys) {
        if (group.size() == 1) return pool.get(group.get(0));
        Set<String> common = new HashSet<>(keys.get(group.get(0)).keySet());
        for (int i : group) common.retainAll(keys.get(i).keySet());
        if (common.isEmpty()) return pool.get(group.get(0));
        Map<String, Double> med = new HashMap<>();
        for (String k : common) {
            List<Double> v = new ArrayList<>();
            for (int i : group) v.add(keys.get(i).get(k));
            med.put(k, median(v));
        }
        int best = group.get(0);
        double bestScore = Double.MAX_VALUE;
        for (int i : group) {
            List<Double> dev = new ArrayList<>();
            for (String k : common) dev.add(Math.abs(keys.get(i).get(k) - med.get(k)));
            double score = median(dev);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return pool.get(best);
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private JSONArray search(String title, String artist) throws IOException, JSONException {
        File cached = new File(cacheDir, cacheKey(title, artist) + ".json");
        if (cached.exists()) return new JSONArray(new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8));
        String primary = primaryArtist(artist);
        String qTitle = searchTitle(title);
        String body = fetch(baseUrl + "/api/search?track_name=" + URLEncoder.encode(qTitle, "UTF-8")
                + "&artist_name=" + URLEncoder.encode(primary, "UTF-8"));
        if (new JSONArray(body).length() == 0) {
            // Structured search misses "A ft. B" style credits; free text finds them.
            body = fetch(baseUrl + "/api/search?q=" + URLEncoder.encode(qTitle + " " + primary, "UTF-8"));
        }
        cacheDir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(cached)) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return new JSONArray(body);
    }

    private static final java.util.regex.Pattern VIDEO_LABEL = java.util.regex.Pattern.compile(
            "\\s*[(\\[](?:official\\s+)?(?:music\\s+|lyrics?\\s+)?(?:video|audio|visuali[sz]er|lyrics?)[)\\]]",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "Taste Back (Lyric Video)" -> "Taste Back": LRCLIB has no entries under video labels. */
    public static String searchTitle(String title) {
        return title == null ? "" : VIDEO_LABEL.matcher(title).replaceAll("").trim();
    }

    /** Cache file name; non-Latin titles would all collapse to "_", so they get a hash. */
    static String cacheKey(String title, String artist) {
        String raw = Lrc.norm(artist) + "__" + Lrc.norm(title);
        String slug = raw.replaceAll("[^a-z0-9]+", "_");
        slug = slug.substring(0, Math.min(100, slug.length()));
        if (raw.chars().allMatch(c -> c < 128)) return slug;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(slug).append('_');
            for (int i = 0; i < 5; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return slug + "_" + Integer.toHexString(raw.hashCode());
        }
    }

    /** "Tyla ft. Zara Larsson" -> "Tyla" (also feat./featuring/&/,/;/x/with). */
    public static String primaryArtist(String artist) {
        if (artist == null) return "";
        return artist.split("(?i)\\s+(?:ft\\.?|feat\\.?|featuring|x|with)\\s+|\\s*[&,;]\\s*")[0].trim();
    }

    private String fetch(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= retryWaitMs.length; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(8_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("User-Agent", userAgent);
                int code = c.getResponseCode();
                if (code == 200) return read(c.getInputStream());
                if (code < 500) throw new IOException("lrclib http " + code);
                last = new IOException("lrclib http " + code);
            } catch (IOException e) {   // includes timeouts and TLS failures
                if (e.getMessage() != null && e.getMessage().startsWith("lrclib http 4")) throw e;
                last = e;
            } finally {
                c.disconnect();
            }
            if (attempt < retryWaitMs.length) {
                try {
                    Thread.sleep(retryWaitMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
