import dev.kbobbili.xitelyrics.lyrics.Lrc;
import dev.kbobbili.xitelyrics.lyrics.LrcLibClient;
import dev.kbobbili.xitelyrics.recognition.ShazamClient;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase 4 desktop tests (no network): response parsing for every outcome,
 * LRC edge cases, and Java-vs-Python equivalence of lyrics selection on all
 * LRCLIB responses cached during Phase 2. Run: tools/phase4/test.sh
 */
public class ClientTests {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        shazamParsing();
        lrcParsing();
        selectionEquivalence();
        networkBehaviour();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures != 0) System.exit(1);
    }

    static void shazamParsing() throws Exception {
        // A real cached Phase 2 response.
        Path dir = Path.of("analysis/recog");
        JSONObject real = null;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                JSONObject o = new JSONObject(Files.readString(p));
                JSONObject resp = o.optJSONObject("response");
                if (resp != null && resp.optJSONArray("matches") != null && resp.getJSONArray("matches").length() > 0) {
                    real = o;
                    break;
                }
            }
        }
        JSONObject resp = real.getJSONObject("response");
        ShazamClient.Result r = ShazamClient.parse(resp.toString(), 200, 300);
        double want = resp.getJSONArray("matches").getJSONObject(0).getDouble("offset");
        check(r.status == ShazamClient.Status.MATCH && r.offsetS == want
                        && resp.getJSONObject("track").getString("title").equals(r.title),
                "real response parses: " + r);
        check(ShazamClient.parse("{\"matches\":[],\"tagid\":\"x\"}", 200, 1).status == ShazamClient.Status.NO_MATCH,
                "empty matches -> NO_MATCH");
        check(ShazamClient.parse("{\"tagid\":\"x\"}", 200, 1).status == ShazamClient.Status.NO_MATCH,
                "missing matches -> NO_MATCH");
        check(ShazamClient.parse("{\"matches\":[{\"id\":\"1\"}]}", 200, 1).status == ShazamClient.Status.ERROR,
                "match without offset -> ERROR");
        check(ShazamClient.parse("{\"matches\":[", 200, 1).status == ShazamClient.Status.ERROR,
                "truncated JSON -> ERROR");
        check(ShazamClient.parse("<html>busy</html>", 200, 1).status == ShazamClient.Status.ERROR,
                "HTML body -> ERROR");
        check(ShazamClient.parse("", 200, 1).status == ShazamClient.Status.ERROR, "empty body -> ERROR");
        check(ShazamClient.parseRetryAfter("30") == 30 && ShazamClient.parseRetryAfter(null) == 0
                        && ShazamClient.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT") == 0
                        && ShazamClient.parseRetryAfter("-5") == 0,
                "Retry-After parsing (seconds, missing, HTTP-date, negative)");
    }

    static void lrcParsing() {
        List<Lrc.Line> l = Lrc.parse("[ar:x]\n[00:01.50]one\n[00:03.00][00:10.25]chorus\n[00:05.00]\n[01:02.5]late\n");
        check(l.size() == 5 && l.get(0).t == 1.5 && l.get(1).text.equals("chorus") && l.get(3).text.equals("chorus")
                        && l.get(2).text.isEmpty() && Math.abs(l.get(4).t - 62.5) < 1e-9,
                "LRC: fractional, repeated stamps, blank gap line, metadata ignored");
        List<Lrc.Line> o = Lrc.parse("[offset:+500]\n[00:02.00]a\n");
        check(Math.abs(o.get(0).t - 1.5) < 1e-9, "LRC: [offset:+500] shifts lines 0.5 s earlier");
        check(Lrc.lineAt(l, 0.5) == -1 && Lrc.lineAt(l, 1.5) == 0 && Lrc.lineAt(l, 4) == 1 && Lrc.lineAt(l, 100) == 4,
                "lineAt: before first, exact, between, after last");
        check(Lrc.norm("Beyoncé (feat. JAY-Z)").equals("beyonce") && Lrc.norm("SHE DID IT AGAIN ft. Zara").equals("she did it again"),
                "norm: accents, (feat.), ft. tails");
        check(Lrc.parse(null).isEmpty() && Lrc.parse("no stamps here").isEmpty(), "LRC: null / unstamped input");
    }

    /** Local server that misbehaves on purpose: status codes, gzip, slowness. */
    static HttpServer server(int[] codes, String body, boolean gzip, int delayMs, AtomicInteger hits) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            int n = hits.getAndIncrement();
            int code = codes[Math.min(n, codes.length - 1)];
            ex.getRequestBody().readAllBytes();
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            if (gzip) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (GZIPOutputStream g = new GZIPOutputStream(b)) { g.write(out); }
                out = b.toByteArray();
                ex.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            if (code == 429) ex.getResponseHeaders().add("Retry-After", "30");
            ex.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
            if (out.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(out); }
            ex.close();
        });
        srv.start();
        return srv;
    }

    static String base(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    static void networkBehaviour() throws Exception {
        byte[] sig = new byte[64];
        String realResp = null;
        try (var files = Files.list(Path.of("analysis/recog"))) {
            for (Path p : (Iterable<Path>) files::iterator) {
                JSONObject resp = new JSONObject(Files.readString(p)).optJSONObject("response");
                if (resp != null && resp.optJSONArray("matches") != null && resp.getJSONArray("matches").length() > 0) {
                    realResp = resp.toString();
                    break;
                }
            }
        }
        AtomicInteger hits = new AtomicInteger();

        HttpServer s = server(new int[] {429}, "", false, 0, hits);
        ShazamClient.Result r = new ShazamClient("t", base(s) + "/%s/%s").send(sig, 128000);
        check(r.status == ShazamClient.Status.RATE_LIMITED && r.retryAfterS == 30, "Shazam 429 -> RATE_LIMITED, Retry-After 30: " + r);
        s.stop(0);

        s = server(new int[] {500}, "oops", false, 0, hits);
        r = new ShazamClient("t", base(s) + "/%s/%s").send(sig, 128000);
        check(r.status == ShazamClient.Status.ERROR && r.httpCode == 500, "Shazam 500 -> ERROR: " + r);
        s.stop(0);

        s = server(new int[] {200}, realResp, true, 0, hits);
        r = new ShazamClient("t", base(s) + "/%s/%s").send(sig, 128000);
        check(r.status == ShazamClient.Status.MATCH, "Shazam gzip body -> MATCH: " + r);
        s.stop(0);

        s = server(new int[] {200}, "{\"matches\":[{", true, 0, hits);
        r = new ShazamClient("t", base(s) + "/%s/%s").send(sig, 128000);
        check(r.status == ShazamClient.Status.ERROR, "Shazam truncated gzip JSON -> ERROR: " + r);
        s.stop(0);

        s = server(new int[] {200}, realResp, false, 12_000, hits);
        long t0 = System.currentTimeMillis();
        r = new ShazamClient("t", base(s) + "/%s/%s").send(sig, 128000);
        long took = System.currentTimeMillis() - t0;
        check(r.status == ShazamClient.Status.ERROR && took < 11_500, "Shazam slow server -> timeout ERROR in " + took + " ms: " + r);
        s.stop(0);

        r = new ShazamClient("t", "http://127.0.0.1:9/%s/%s").send(sig, 128000);
        check(r.status == ShazamClient.Status.ERROR, "Shazam connection refused -> ERROR: " + r);

        File cache = Files.createTempDirectory("lrc").toFile();
        String lrcBody = Files.readString(Path.of("analysis/lrclib/harry_styles_american_girls.json"));
        hits.set(0);
        s = server(new int[] {503, 503, 200}, lrcBody, false, 0, hits);
        LrcLibClient lc = new LrcLibClient(cache, "t", base(s), new int[] {10, 10, 10});
        LrcLibClient.Choice c = lc.choose("American Girls", "Harry Styles");
        check(c.status == LrcLibClient.Status.OK && hits.get() == 3, "LRCLIB 503,503,200 -> OK after " + hits.get() + " attempts");
        lc.choose("American Girls", "Harry Styles");
        check(hits.get() == 3, "LRCLIB second lookup served from disk cache");
        s.stop(0);

        hits.set(0);
        s = server(new int[] {503}, "", false, 0, hits);
        c = new LrcLibClient(cache, "t", base(s), new int[] {10, 10, 10}).choose("Other", "Song");
        check(c.status == LrcLibClient.Status.LOOKUP_FAILED && hits.get() == 4, "LRCLIB always 503 -> LOOKUP_FAILED after 4 attempts");
        s.stop(0);

        hits.set(0);
        s = server(new int[] {404}, "", false, 0, hits);
        c = new LrcLibClient(cache, "t", base(s), new int[] {10, 10, 10}).choose("Other", "Song");
        check(c.status == LrcLibClient.Status.LOOKUP_FAILED && hits.get() == 1, "LRCLIB 404 -> LOOKUP_FAILED, no retry");
        s.stop(0);

        c = new LrcLibClient(cache, "t", "http://127.0.0.1:9", new int[] {10, 10, 10}).choose("Other", "Song");
        check(c.status == LrcLibClient.Status.LOOKUP_FAILED, "LRCLIB unreachable -> LOOKUP_FAILED");

        s = server(new int[] {200}, "[]", false, 0, hits);
        c = new LrcLibClient(cache, "t", base(s), new int[] {10}).choose("Nothing", "Here");
        check(c.status == LrcLibClient.Status.NONE, "LRCLIB empty result -> NONE");
        s.stop(0);
    }

    static void selectionEquivalence() throws Exception {
        Path ref = Path.of("analysis/lrclib_choices.json");
        if (!Files.exists(ref)) {
            check(false, "selection equivalence: run tools/phase4/dump_choices.py first");
            return;
        }
        JSONArray cases = new JSONArray(Files.readString(ref, StandardCharsets.UTF_8));
        int same = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            JSONArray results = new JSONArray(Files.readString(Path.of("analysis/lrclib", c.getString("file")), StandardCharsets.UTF_8));
            LrcLibClient.Choice j = LrcLibClient.select(results, c.getString("title"), c.getString("artist"));
            String py = c.getString("status").toUpperCase();
            boolean ok = j.status.name().equals(py) && j.candidateId == c.getLong("id") && j.lines.size() == c.getInt("lines");
            if (ok) same++;
            else System.out.printf("  mismatch %s / %s: java %s %d (%d lines) vs python %s %d (%d lines)%n",
                    c.getString("title"), c.getString("artist"), j.status, j.candidateId, j.lines.size(),
                    py, c.getLong("id"), c.getInt("lines"));
        }
        check(same == cases.length(), "lyrics selection matches Python on " + same + "/" + cases.length() + " songs");
    }
}
