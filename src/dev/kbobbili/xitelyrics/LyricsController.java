package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import dev.kbobbili.xitelyrics.lyrics.LrcLibClient;
import dev.kbobbili.xitelyrics.recognition.ShazamClient;
import dev.kbobbili.xitelyrics.sync.SyncEngine;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide owner of the SyncEngine. Everything touching the engine runs on
 * the main thread; clip recording + recognition run on CaptureService's worker,
 * lyrics lookups on their own thread, and results are posted back here.
 *
 * Display output goes to listeners (the overlay, Phase 6) and to the log.
 */
public final class LyricsController {
    public interface DisplayListener {
        void onDisplay(SyncEngine.Display d);
    }

    private static LyricsController instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lyricsWorker = Executors.newSingleThreadExecutor();
    private final ShazamClient shazam = new ShazamClient(ClientSelfTest.userAgent());
    private final LrcLibClient lrclib;
    private final SyncEngine engine;
    private DisplayListener listener;
    private String shownLine;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            engine.tick(now);
            publish(now);
            main.postDelayed(this, 100);
        }
    };

    public static synchronized LyricsController get(Context ctx) {
        if (instance == null) instance = new LyricsController(ctx.getApplicationContext());
        return instance;
    }

    private LyricsController(Context app) {
        this.app = app;
        lrclib = new LrcLibClient(new File(app.getCacheDir(), "lrclib"), ClientSelfTest.userAgent());
        engine = new SyncEngine(new SyncEngine.Actions() {
            @Override
            public void requestCapture(int itemGen, int clipEpoch, int clipMs) {
                capture(itemGen, clipEpoch, clipMs);
            }

            @Override
            public void requestLyrics(int itemGen, String title, String artist) {
                lyricsWorker.execute(() -> {
                    LrcLibClient.Choice c = lrclib.choose(title, artist);
                    main.post(() -> engine.onLyrics(itemGen, title, artist, c, SystemClock.elapsedRealtime()));
                });
            }

            @Override
            public void log(String type, Object... kv) {
                Events.log(type, kv);
            }
        });
        if (!ticking) {
            ticking = true;
            main.post(tick);
        }
    }

    public void setDisplayListener(DisplayListener l) {
        listener = l;
    }

    // ------------------------------------------------ inputs (main thread)

    public void onSession(boolean present) {
        engine.onSession(present, SystemClock.elapsedRealtime());
    }

    public void onPlayback(int state, long position, long updateRt, float speed) {
        engine.onPlayback(state, position, updateRt, speed, SystemClock.elapsedRealtime());
    }

    public void onForeground(boolean xiteInFront) {
        engine.onForeground(xiteInFront, SystemClock.elapsedRealtime());
    }

    public void onPanel(String title, String artist, String progress) {
        engine.onPanel(title, artist, videoDurationS(progress), SystemClock.elapsedRealtime());
    }

    /** "00:47 / 03:10" -> 190 (seconds), 0 if unreadable. */
    static double videoDurationS(String progress) {
        if (progress == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/\\s*(\\d+):(\\d{2})").matcher(progress);
        return m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : 0;
    }

    // ------------------------------------------------ capture + recognition

    private void capture(int gen, int epoch, int clipMs) {
        CaptureService svc = CaptureService.running();
        if (svc == null) {
            ShazamClient.Result r = ShazamClientResults.noCapture();
            main.post(() -> engine.onRecognition(gen, epoch, 0, 0, r, SystemClock.elapsedRealtime()));
            return;
        }
        svc.recordClip(clipMs, clip -> {
            ShazamClient.Result r = clip == null ? ShazamClientResults.noCapture() : shazam.recognize(clip.pcm);
            Events.log("sync_clip", "gen", gen, "epoch", epoch, "firstSampleRt", clip == null ? 0 : clip.firstSampleRt,
                    "result", r.toString());
            long first = clip == null ? 0 : clip.firstSampleRt;
            long end = clip == null ? 0 : clip.endRt;
            main.post(() -> engine.onRecognition(gen, epoch, first, end, r, SystemClock.elapsedRealtime()));
        });
    }

    /** Look this far ahead so a line's fade-in completes when the line starts. */
    private static final long LEAD_MS = LyricsOverlay.FADE_MS;

    private void publish(long now) {
        SyncEngine.Display d = engine.display(now + LEAD_MS);
        String line = d.visible ? d.line : null;
        if (!Objects.equals(line, shownLine)) {
            shownLine = line;
            Events.log("display", "line", line, "next", d.next, "mode", engine.mode().name());
        }
        if (listener != null) listener.onDisplay(d);
    }
}
