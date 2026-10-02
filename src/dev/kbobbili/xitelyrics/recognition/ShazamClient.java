package dev.kbobbili.xitelyrics.recognition;

import dev.kbobbili.xitelyrics.fingerprint.SongRecFingerprint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Unofficial Shazam recognition (the request shazamio 0.8.1 sends).
 *
 * Phase 2 proved the returned offset is the song-timeline position of the
 * FIRST sample passed in, so callers must timestamp that sample themselves;
 * response latency never enters the timing.
 *
 * Blocking; call from a worker thread. Never throws: every outcome, including
 * 429s, timeouts and malformed bodies, comes back as a Result.
 */
public final class ShazamClient {
    private static final String URL_FMT = "https://amp.shazam.com/discovery/v5/en-US/US/android/-/tag/%s/%s"
            + "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true"
            + "&hubv5minorversion=v5.1&hidelb=true&video=v3";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int SAMPLE_RATE = 16_000;

    public enum Status { MATCH, NO_MATCH, RATE_LIMITED, ERROR }

    public static final class Result {
        public final Status status;
        public final String title, artist, trackKey;
        /** Seconds into the matched recording of the first submitted sample. */
        public final double offsetS;
        public final double timeskew, freqskew;
        public final int httpCode;
        public final long latencyMs;
        /** Seconds the server asked us to wait (429 Retry-After), or 0. */
        public final int retryAfterS;
        public final String error;

        Result(Status status, String title, String artist, String trackKey, double offsetS, double timeskew,
                double freqskew, int httpCode, long latencyMs, int retryAfterS, String error) {
            this.status = status;
            this.title = title;
            this.artist = artist;
            this.trackKey = trackKey;
            this.offsetS = offsetS;
            this.timeskew = timeskew;
            this.freqskew = freqskew;
            this.httpCode = httpCode;
            this.latencyMs = latencyMs;
            this.retryAfterS = retryAfterS;
            this.error = error;
        }

        static Result failure(Status s, int code, long latencyMs, int retryAfterS, String error) {
            return new Result(s, null, null, null, Double.NaN, Double.NaN, Double.NaN, code, latencyMs,
                    retryAfterS, error);
        }

        @Override
        public String toString() {
            return status == Status.MATCH
                    ? String.format(Locale.US, "MATCH %s / %s offset=%.3f skew=%.2e (%d ms)", title, artist,
                            offsetS, timeskew, latencyMs)
                    : String.format(Locale.US, "%s http=%d %s (%d ms)", status, httpCode, error, latencyMs);
        }
    }

    private final String userAgent;
    private final String urlFmt;

    public ShazamClient(String userAgent) {
        this(userAgent, URL_FMT);
    }

    /** Test seam: urlFmt takes two %s (the request UUIDs). */
    public ShazamClient(String userAgent, String urlFmt) {
        this.userAgent = userAgent;
        this.urlFmt = urlFmt;
    }

    /** Fingerprints the clip (keep it <= 10 s) and asks Shazam. */
    public Result recognize(short[] pcm) {
        byte[] sig = SongRecFingerprint.signature(pcm);
        return send(sig, pcm.length);
    }

    public Result send(byte[] signature, int samples) {
        long t0 = System.nanoTime();
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject()
                    .put("timezone", "Europe/Moscow")
                    .put("signature", new JSONObject()
                            .put("uri", "data:audio/vnd.shazam.sig;base64,"
                                    + java.util.Base64.getEncoder().encodeToString(signature))
                            .put("samplems", (long) samples * 1000 / SAMPLE_RATE))
                    .put("timestamp", System.currentTimeMillis())
                    .put("context", new JSONObject())
                    .put("geolocation", new JSONObject());
            String u1 = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            String u2 = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            c = (HttpURLConnection) new URL(String.format(urlFmt, u1, u2)).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Shazam-Platform", "IPHONE");
            c.setRequestProperty("X-Shazam-AppVersion", "14.1.0");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US");
            c.setRequestProperty("Accept-Encoding", "gzip");
            c.setRequestProperty("User-Agent", userAgent);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (code == 429) {
                return Result.failure(Status.RATE_LIMITED, code, ms, parseRetryAfter(c.getHeaderField("Retry-After")),
                        "rate limited");
            }
            if (code / 100 != 2) return Result.failure(Status.ERROR, code, ms, 0, "http " + code);
            String text = readBody(c);
            return parse(text, code, (System.nanoTime() - t0) / 1_000_000);
        } catch (SocketTimeoutException e) {
            return Result.failure(Status.ERROR, 0, (System.nanoTime() - t0) / 1_000_000, 0, "timeout");
        } catch (Exception e) {   // IO, JSON (checked on Android, unchecked in desktop org.json), runtime
            return Result.failure(Status.ERROR, 0, (System.nanoTime() - t0) / 1_000_000, 0, e.toString());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Parses a response body; malformed JSON is an ERROR, a body without matches is NO_MATCH. */
    public static Result parse(String text, int code, long latencyMs) {
        try {
            JSONObject r = new JSONObject(text);
            JSONArray matches = r.optJSONArray("matches");
            if (matches == null || matches.length() == 0) {
                return Result.failure(Status.NO_MATCH, code, latencyMs, 0, "no match");
            }
            JSONObject m = matches.getJSONObject(0);
            if (!m.has("offset")) return Result.failure(Status.ERROR, code, latencyMs, 0, "match without offset");
            JSONObject t = r.optJSONObject("track");
            return new Result(Status.MATCH,
                    t == null ? null : t.optString("title", null),
                    t == null ? null : t.optString("subtitle", null),
                    t == null ? null : t.optString("key", null),
                    m.getDouble("offset"), m.optDouble("timeskew", 0), m.optDouble("frequencyskew", 0),
                    code, latencyMs, 0, null);
        } catch (JSONException e) {
            return Result.failure(Status.ERROR, code, latencyMs, 0, "malformed response: " + e.getMessage());
        }
    }

    public static int parseRetryAfter(String h) {
        if (h == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(h.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        InputStream in = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
