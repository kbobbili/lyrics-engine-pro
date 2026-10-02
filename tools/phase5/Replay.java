import dev.kbobbili.xitelyrics.lyrics.LrcLibClient;
import dev.kbobbili.xitelyrics.recognition.ShazamClient;
import dev.kbobbili.xitelyrics.sync.SyncEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Phase 5 end-to-end replay: drives the real SyncEngine with a recorded TV
 * session (audio + events.jsonl) on a simulated clock. Captures cut 8 s from the
 * recording at the requested moment; recognition uses the real ShazamClient but
 * first reuses Phase 2's cached responses (keyed by SHA-1 of the PCM, identical
 * signatures); lyrics use LrcLibClient on the Phase 2 LRCLIB cache.
 *
 * Writes analysis/<session>-replay.json: every display change plus per-item stats.
 * Usage: tools/phase5/replay.sh analysis/sessions/<id>
 */
public class Replay {
    static final int RATE = 16000;

    // ----- session audio and clock (port of tools/phase2/session.py)
    static short[] pcm;
    static double[] tsFrame, off;
    static double slope;

    static void loadSession(Path dir) throws Exception {
        File[] metas = dir.toFile().listFiles((d, n) -> n.startsWith("chunk-") && n.endsWith(".json"));
        Arrays.sort(metas);
        List<short[]> parts = new ArrayList<>();
        List<double[]> ts = new ArrayList<>();
        int total = 0;
        for (File m : metas) {
            JSONObject meta = new JSONObject(Files.readString(m.toPath()));
            byte[] wav = Files.readAllBytes(Path.of(m.getPath().replace(".json", ".wav")));
            short[] s = new short[(wav.length - 44) / 2];
            ByteBuffer.wrap(wav, 44, wav.length - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
            parts.add(s);
            total += s.length;
            JSONArray reads = meta.getJSONArray("reads");
            for (int i = 0; i < reads.length(); i++) {
                JSONObject r = reads.getJSONObject(i);
                if (r.has("tsNs")) ts.add(new double[] {r.getLong("tsFrame"), r.getLong("tsNs") / 1e6});
            }
        }
        pcm = new short[total];
        int p = 0;
        for (short[] s : parts) {
            System.arraycopy(s, 0, pcm, p, s.length);
            p += s.length;
        }
        int n = ts.size();
        tsFrame = new double[n];
        double[] tn = new double[n];
        for (int i = 0; i < n; i++) {
            tsFrame[i] = ts.get(i)[0];
            tn[i] = ts.get(i)[1];
        }
        double mx = Arrays.stream(tsFrame).average().orElse(0), my = Arrays.stream(tn).average().orElse(0);
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (tsFrame[i] - mx) * (tn[i] - my);
            sxx += (tsFrame[i] - mx) * (tsFrame[i] - mx);
        }
        slope = sxy / sxx;
        off = new double[n];
        for (int i = 0; i < n; i++) off[i] = tn[i] - slope * tsFrame[i];
    }

    static double localOffset(double frame) {
        int lo = lower(frame - 5 * RATE), hi = lower(frame + 5 * RATE);
        if (hi <= lo) return off[Math.min(Math.max(lower(frame), 0), off.length - 1)];
        double[] w = Arrays.copyOfRange(off, lo, hi);
        Arrays.sort(w);
        return w.length % 2 == 1 ? w[w.length / 2] : (w[w.length / 2 - 1] + w[w.length / 2]) / 2;
    }

    static int lower(double v) {
        int lo = 0, hi = tsFrame.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (tsFrame[m] < v) lo = m + 1; else hi = m;
        }
        return lo;
    }

    static double frameToRt(double f) { return localOffset(f) + slope * f; }

    static long rtToFrame(double rt) {
        double f = (rt - off[0]) / slope;
        for (int i = 0; i < 3; i++) f = (rt - localOffset(f)) / slope;
        return Math.round(f);
    }

    // ----- recognition with the Phase 2 cache
    static ShazamClient shazam = new ShazamClient("Dalvik/2.1.0 (Linux; U; Android 14; onn 4K Pro)");
    static int networkCalls, cacheHits;

    static ShazamClient.Result recognize(short[] clip) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(clip.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(clip);
        byte[] h = MessageDigest.getInstance("SHA-1").digest(bb.array());
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 8; i++) key.append(String.format("%02x", h[i]));
        Path cached = Path.of("analysis/recog", key + ".json");
        if (Files.exists(cached)) {
            JSONObject o = new JSONObject(Files.readString(cached));
            if (o.has("response")) {
                cacheHits++;
                return ShazamClient.parse(o.getJSONObject("response").toString(), 200, (long) (o.optDouble("latency_s", 0.3) * 1000));
            }
        }
        networkCalls++;
        Thread.sleep(6_000);                       // stay near the rate tested in Phase 2
        ShazamClient.Result r = shazam.recognize(clip);
        if (r.status == ShazamClient.Status.MATCH || r.status == ShazamClient.Status.NO_MATCH) {
            // cache in the Phase 2 format (response only) so later replays are offline
            JSONObject resp = new JSONObject();
            if (r.status == ShazamClient.Status.MATCH) {
                resp.put("matches", new JSONArray().put(new JSONObject().put("offset", r.offsetS)
                        .put("timeskew", r.timeskew).put("frequencyskew", r.freqskew)));
                resp.put("track", new JSONObject().put("title", r.title).put("subtitle", r.artist).put("key", r.trackKey));
            } else {
                resp.put("matches", new JSONArray());
            }
            Files.writeString(cached, new JSONObject().put("response", resp).put("latency_s", r.latencyMs / 1000.0)
                    .put("label", "replay").toString());
        }
        return r;
    }

    // ----- simulation
    record Pending(long at, Runnable run) {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        loadSession(dir);
        double startRt = frameToRt(0), endRt = frameToRt(pcm.length);
        List<JSONObject> events = new ArrayList<>();
        for (String line : Files.readAllLines(dir.getParent().getParent().resolve("events.jsonl"), StandardCharsets.UTF_8)) {
            try { events.add(new JSONObject(line)); } catch (Exception ignored) { }
        }
        int last = 0;
        for (int i = 1; i < events.size(); i++) if (events.get(i).getLong("rt") < events.get(i - 1).getLong("rt") - 60_000) last = i;
        events = events.subList(last, events.size());

        PriorityQueue<Pending> queue = new PriorityQueue<>((a, b) -> Long.compare(a.at, b.at));
        long[] now = {0};
        List<JSONObject> timeline = new ArrayList<>();
        Map<Integer, int[]> calls = new HashMap<>();      // itemGen -> {captures}
        LrcLibClient lrclib = new LrcLibClient(new File("analysis/lrclib"), "xite-lyrics-replay");
        SyncEngine[] eng = new SyncEngine[1];

        SyncEngine.Actions actions = new SyncEngine.Actions() {
            public void requestCapture(int gen, int epoch) {
                long first = now[0];
                calls.computeIfAbsent(gen, g -> new int[1])[0]++;
                long f0 = rtToFrame(first);
                if (f0 < 0 || f0 + 8L * RATE > pcm.length) return;
                short[] clip = Arrays.copyOfRange(pcm, (int) f0, (int) f0 + 8 * RATE);
                long clipEnd = first + 8_000;
                try {
                    ShazamClient.Result r = recognize(clip);
                    queue.add(new Pending(clipEnd + r.latencyMs,
                            () -> eng[0].onRecognition(gen, epoch, first, clipEnd, r, now[0])));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            public void requestLyrics(int gen, String title, String artist) {
                LrcLibClient.Choice c = lrclib.choose(title, artist);
                queue.add(new Pending(now[0] + 400, () -> eng[0].onLyrics(gen, title, artist, c, now[0])));
            }

            public void log(String type, Object... kv) {
                JSONObject o = new JSONObject().put("rt", now[0]).put("type", type);
                for (int i = 0; i + 1 < kv.length; i += 2) {
                    Object v = kv[i + 1];
                    if (v instanceof Double d && (d.isNaN() || d.isInfinite())) v = null;
                    o.put(String.valueOf(kv[i]), v == null ? JSONObject.NULL : v);
                }
                timeline.add(o);
            }
        };
        SyncEngine e = new SyncEngine(actions);
        eng[0] = e;

        int ei = 0;
        while (ei < events.size() && events.get(ei).getLong("rt") < startRt) ei++;
        // Bring the engine up to the state at recording start: session + foreground + last playback.
        e.onSession(true, (long) startRt);
        e.onForeground(true, (long) startRt);
        String shown = null;
        for (long t = (long) startRt; t < (long) endRt; t += 100) {
            now[0] = t;
            while (ei < events.size() && events.get(ei).getLong("rt") <= t) {
                JSONObject ev = events.get(ei++);
                switch (ev.getString("type")) {
                    case "playback" -> e.onPlayback(ev.getInt("state"), ev.getLong("position"), ev.getLong("updateRt"),
                            (float) ev.getDouble("speed"), t);
                    case "panel" -> e.onPanel(ev.optString("title", null), ev.optString("artist", null), t);
                    case "xite_foreground" -> e.onForeground(ev.getBoolean("value"), t);
                    case "session_gone", "session_destroyed" -> e.onSession(false, t);
                    case "session_attached" -> e.onSession(true, t);
                    default -> { }
                }
            }
            while (!queue.isEmpty() && queue.peek().at <= t) queue.poll().run.run();
            e.tick(t);
            SyncEngine.Display d = e.display(t);
            String cur = d.visible ? d.line : null;
            if (!java.util.Objects.equals(cur, shown)) {
                timeline.add(new JSONObject().put("rt", t).put("type", "display").put("line", cur == null ? JSONObject.NULL : cur)
                        .put("mode", e.mode().name()).put("itemGen", e.itemGen()));
                shown = cur;
            }
        }
        JSONObject out = new JSONObject().put("timeline", new JSONArray(timeline))
                .put("shazamNetworkCalls", networkCalls).put("shazamCacheHits", cacheHits)
                .put("capturesPerGen", new JSONObject(calls));
        Path outPath = Path.of("analysis", dir.getFileName() + "-replay.json");
        Files.writeString(outPath, out.toString(1));
        System.out.printf("replayed %.1f min: %d display changes, Shazam cache hits %d, network calls %d -> %s%n",
                (endRt - startRt) / 60000, timeline.stream().filter(o -> o.getString("type").equals("display")).count(),
                cacheHits, networkCalls, outPath);
    }
}
