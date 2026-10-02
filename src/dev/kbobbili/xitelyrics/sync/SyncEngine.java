package dev.kbobbili.xitelyrics.sync;

import dev.kbobbili.xitelyrics.lyrics.Lrc;
import dev.kbobbili.xitelyrics.lyrics.LrcLibClient;
import dev.kbobbili.xitelyrics.recognition.ShazamClient;

import java.util.List;
import java.util.Locale;

/**
 * Decides what to capture, which recognition results to trust and which lyric
 * line to show. Pure logic: every input carries the elapsed-realtime "now" (ms),
 * all calls must come from one thread, and side effects go through Actions.
 *
 * Model (plan "Clock contract", tuned by Phase 1-2 measurements):
 *   videoPos(t)    = position + speed * (t - updateRt)        while PLAYING
 *   delta          = shazamOffset - videoPos(firstSampleRt)    per accepted match
 *   referencePos(t)= videoPos(t) + delta - devicePipelineOffset
 *
 * Fast start (owner decision 2026-10-02, measured on 17 songs): the first clip
 * is 5 s, taken as soon as the info panel appears (from +2 s), and misses retry
 * at once. Until it matches, lines show provisionally with delta = 0 (XITE's
 * video matched the album track within 0.36 s median at song start), then snap
 * to the measured delta.
 *
 * Validity has two counters:
 *   itemGen   - bumped by a new item, seek, session change or XITE leaving the
 *               front; invalidates the alignment and all in-flight work.
 *   clipEpoch - also bumped by any pause/buffering; drops in-flight clips that
 *               spanned the interruption but keeps the alignment.
 */
public final class SyncEngine {
    public interface Actions {
        /** Record one clip of clipMs now and report back via onRecognition with the same tags. */
        void requestCapture(int itemGen, int clipEpoch, int clipMs);

        /** Fetch lyrics and report back via onLyrics with the same tags. */
        void requestLyrics(int itemGen, String title, String artist);

        void log(String type, Object... kv);
    }

    public enum Mode { WAITING, AD, ACQUIRING, ALIGNED, CONFIRMING, PAUSED }

    /** What the overlay should draw. */
    public static final class Display {
        public static final Display HIDDEN = new Display(false, null, null);
        public final boolean visible;
        public final String line, next;

        /** A visible display with fixed text (overlay previews and tests). */
        public static Display of(String line, String next) {
            return new Display(true, line, next);
        }

        Display(boolean visible, String line, String next) {
            this.visible = visible;
            this.line = line;
            this.next = next;
        }
    }

    // Tunables (ms). Defaults from the plan and Phase 2 measurements.
    static final long FIRST_CLIP_AFTER_START = 2_000;
    static final int FAST_CLIP_MS = 5_000;          // while acquiring
    static final int CLIP_MS = 8_000;               // once aligned (periodic checks, confirmations)
    static final long RETRY_WHILE_ACQUIRING = 500;
    static final long PROVISIONAL_MAX = 30_000;     // unconfirmed provisional lyrics give up
    static final long FIRST_CLIP_AFTER_JOIN = 2_000;
    static final long CLIP_PERIOD = 45_000;
    /** Edits cluster (Homewrecker: two cuts ~15 s apart), so look again soon after one. */
    static final long RECHECK_AFTER_EDIT = 15_000;
    static final long RETRY_AFTER_MISS = 15_000;
    static final long CONFIRM_AFTER_CONFLICT = 5_000;
    static final long REMEASURE_AFTER_RESUME = 3_000;
    static final long AD_PANEL_DEADLINE = 3_000;
    static final long FRESHNESS_CAP = 120_000;
    static final long AGREE_MS = 300;
    static final long JUMP_MS = 1_500;
    static final long RATE_LIMIT_MIN_BACKOFF = 60_000;
    static final long RATE_LIMIT_MAX_BACKOFF = 600_000;
    static final double MAX_LINE_HOLD_S = 10.0;

    private static final int STATE_STOPPED = 1, STATE_PAUSED = 2, STATE_PLAYING = 3, STATE_BUFFERING = 6;

    private final Actions actions;
    private long devicePipelineOffsetMs;

    private Mode mode = Mode.WAITING;
    private int itemGen;
    private int clipEpoch;

    // Playback.
    private boolean sessionPresent;
    private boolean foreground;
    private int state;
    private long position, updateRt;
    private float speed;
    private boolean boundaryPending = true;
    private long itemStartRt = -1;
    private boolean joinedMidItem;

    // Identity.
    private String panelTitle, panelArtist;
    private String recTitle, recArtist;

    // Alignment.
    private boolean aligned;
    private double deltaMs;
    private long lastAcceptedClipEndRt;
    private int agreements;
    private double pendingDeltaMs;
    private boolean pending;
    /** Showing lines at delta = 0 from the panel identity, before the first match. */
    private boolean provisional;

    // Scheduling.
    private long nextCaptureRt = Long.MAX_VALUE;
    private boolean captureInFlight;
    private long rateLimitBackoff = RATE_LIMIT_MIN_BACKOFF;

    // Lyrics.
    private String lyricsFor;          // norm(title)|norm(artist) of the loaded/in-flight lyrics
    private String lyricsTitle;        // raw title of that request
    private boolean lyricsInFlight;
    private List<Lrc.Line> lines;

    public SyncEngine(Actions actions) {
        this.actions = actions;
    }

    public void setDevicePipelineOffsetMs(long ms) {
        devicePipelineOffsetMs = ms;
    }

    public Mode mode() {
        return mode;
    }

    public int itemGen() {
        return itemGen;
    }

    public int clipEpoch() {
        return clipEpoch;
    }

    public double deltaMs() {
        return deltaMs;
    }

    // ---------------------------------------------------------------- inputs

    public void onSession(boolean present, long now) {
        if (present == sessionPresent) return;
        sessionPresent = present;
        newGeneration(present ? "session_attached" : "session_gone", now);
        boundaryPending = true;
        state = 0;
        mode = Mode.WAITING;
    }

    public void onForeground(boolean xiteInFront, long now) {
        if (xiteInFront == foreground) return;
        foreground = xiteInFront;
        if (!xiteInFront) {
            newGeneration("foreground_lost", now);
            mode = Mode.WAITING;
            boundaryPending = true;
        } else if (state == STATE_PLAYING) {
            // Back in front mid-item: reacquire without the intro delay.
            joinedMidItem = true;
            enterAcquiring(now + FIRST_CLIP_AFTER_JOIN, now);
        }
    }

    public void onPlayback(int newState, long newPosition, long newUpdateRt, float newSpeed, long now) {
        int prevState = state;
        double expected = prevState == STATE_PLAYING ? videoPos(newUpdateRt) : position;
        state = newState;
        if (newState == STATE_STOPPED || (newState == STATE_BUFFERING && newPosition == 0)) {
            boundaryPending = true;
        }

        if (newState != STATE_PLAYING) {
            position = newPosition;
            updateRt = newUpdateRt;
            speed = newSpeed;
            if (prevState == STATE_PLAYING) {
                clipEpoch++;                       // in-flight clips spanned an interruption
                captureInFlight = false;
                nextCaptureRt = Long.MAX_VALUE;
                if (mode == Mode.ALIGNED || mode == Mode.CONFIRMING || mode == Mode.ACQUIRING) {
                    mode = Mode.PAUSED;
                    actions.log("sync_paused", "state", newState, "position", newPosition);
                }
            }
            return;
        }

        // PLAYING
        boolean newItem = boundaryPending || itemStartRt < 0;
        boolean reset = !newItem && prevState == STATE_PLAYING && newPosition + JUMP_MS < expected;
        boolean jump = !newItem && Math.abs(newPosition - expected) > JUMP_MS;
        position = newPosition;
        updateRt = newUpdateRt;
        speed = newSpeed;

        if (newItem || reset) {
            boundaryPending = false;
            startItem(newUpdateRt, now);
            return;
        }
        if (jump) {                                // seek within the item
            newGeneration("seek", now);
            joinedMidItem = true;
            enterAcquiring(now + FIRST_CLIP_AFTER_JOIN, now);
            return;
        }
        if (prevState == STATE_PLAYING) {
            // XITE re-reported its position: rebase so referencePos stays continuous.
            double c = newPosition - expected;
            if (aligned && Math.abs(c) >= 1) {
                deltaMs -= c;
                actions.log("sync_rebase", "correctionMs", c, "deltaMs", deltaMs);
            }
            return;
        }
        // Resume from pause/buffering: continuity verified above (no jump).
        if (mode == Mode.PAUSED) {
            mode = aligned ? Mode.ALIGNED : Mode.ACQUIRING;
            scheduleAt(now + REMEASURE_AFTER_RESUME);
            actions.log("sync_resumed", "aligned", aligned);
        }
    }

    public void onPanel(String title, String artist, long now) {
        if (title == null || title.isEmpty()) return;
        boolean changed = !title.equals(panelTitle) || !eq(artist, panelArtist);
        panelTitle = title;
        panelArtist = artist;
        if (mode == Mode.AD) {                     // panel arrived late: it's a song after all
            actions.log("sync_ad_reclassified", "title", title);
            enterAcquiring(now, now);
        }
        if (changed && recTitle == null) prefetchLyrics(title, artist);
    }

    public void onRecognition(int gen, int epoch, long firstSampleRt, long clipEndRt, ShazamClient.Result r,
            long now) {
        if (gen != itemGen || epoch != clipEpoch) {
            actions.log("sync_stale_result", "gen", gen, "itemGen", itemGen, "epoch", epoch, "clipEpoch", clipEpoch);
            return;
        }
        captureInFlight = false;
        switch (r.status) {
            case MATCH:
                rateLimitBackoff = RATE_LIMIT_MIN_BACKOFF;
                onMatch(firstSampleRt, clipEndRt, r, now);
                break;
            case RATE_LIMITED:
                long wait = Math.max(r.retryAfterS * 1000L, rateLimitBackoff);
                rateLimitBackoff = Math.min(rateLimitBackoff * 2, RATE_LIMIT_MAX_BACKOFF);
                actions.log("sync_rate_limited", "waitMs", wait);
                scheduleAt(now + wait);
                break;
            default:                                // NO_MATCH, ERROR
                actions.log("sync_miss", "status", r.status.name(), "error", r.error);
                scheduleAt(now + (aligned ? RETRY_AFTER_MISS : RETRY_WHILE_ACQUIRING));
        }
    }

    public void onLyrics(int gen, String title, String artist, LrcLibClient.Choice c, long now) {
        if (gen != itemGen) return;
        String key = key(title, artist);
        if (!key.equals(lyricsFor)) return;        // superseded by a different identity
        lyricsInFlight = false;
        lines = c.status == LrcLibClient.Status.OK ? c.lines : null;
        actions.log("sync_lyrics", "status", c.status.name(), "id", c.candidateId,
                "lines", lines == null ? 0 : lines.size(), "title", title);
        if (lines != null && !aligned && !joinedMidItem && mode == Mode.ACQUIRING && panelTitle != null
                && sameTitle(title, panelTitle) && now - itemStartRt < PROVISIONAL_MAX) {
            provisional = true;
            actions.log("sync_provisional", "title", title);
        }
        if (c.status == LrcLibClient.Status.NONE && panelTitle != null
                && !key(panelTitle, panelArtist).equals(key) && sameTitle(title, panelTitle)) {
            requestLyrics(panelTitle, panelArtist);
        }
    }

    /** Housekeeping; call regularly (e.g. every 100-250 ms). */
    public void tick(long now) {
        if (mode == Mode.ACQUIRING && itemStartRt >= 0 && !joinedMidItem && panelTitle == null
                && now - itemStartRt >= AD_PANEL_DEADLINE && recTitle == null) {
            mode = Mode.AD;
            nextCaptureRt = Long.MAX_VALUE;
            actions.log("sync_ad");
        }
        if (provisional && !aligned && now - itemStartRt > PROVISIONAL_MAX) {
            provisional = false;
            actions.log("sync_provisional_expired");
        }
        if ((mode == Mode.ALIGNED || mode == Mode.CONFIRMING) && aligned
                && now - lastAcceptedClipEndRt > FRESHNESS_CAP) {
            aligned = false;
            pending = false;
            mode = Mode.ACQUIRING;
            actions.log("sync_expired");
        }
        boolean capturing = foreground && state == STATE_PLAYING
                && (mode == Mode.ACQUIRING || mode == Mode.ALIGNED || mode == Mode.CONFIRMING);
        // A new item's first clip waits for the panel (songs) or the ad decision (no panel),
        // so ads never cost a Shazam call.
        boolean awaitingPanel = mode == Mode.ACQUIRING && !joinedMidItem && panelTitle == null && recTitle == null;
        if (capturing && !awaitingPanel && !captureInFlight && now >= nextCaptureRt) {
            captureInFlight = true;
            nextCaptureRt = Long.MAX_VALUE;
            actions.requestCapture(itemGen, clipEpoch, aligned ? CLIP_MS : FAST_CLIP_MS);
        }
    }

    /** Line to draw at now. */
    public Display display(long now) {
        boolean tentative = !aligned && provisional;
        if (!foreground || lines == null || !(aligned || tentative)) return Display.HIDDEN;
        if (mode != Mode.ALIGNED && mode != Mode.PAUSED && !(tentative && mode == Mode.ACQUIRING)) {
            return Display.HIDDEN;
        }
        if (mode == Mode.PAUSED && state != STATE_PAUSED) return Display.HIDDEN;   // buffering etc.
        double d = aligned ? deltaMs : 0;
        double refS = (videoPos(now) + d - devicePipelineOffsetMs) / 1000.0;
        int i = Lrc.lineAt(lines, refS);
        if (i < 0) return Display.HIDDEN;
        Lrc.Line cur = lines.get(i);
        double until = i + 1 < lines.size() ? lines.get(i + 1).t : cur.t + MAX_LINE_HOLD_S;
        if (cur.text.isEmpty() || refS >= Math.min(until, cur.t + MAX_LINE_HOLD_S)) return Display.HIDDEN;
        String next = null;
        if (i + 1 < lines.size() && !lines.get(i + 1).text.isEmpty()) next = lines.get(i + 1).text;
        return new Display(true, cur.text, next);
    }

    // -------------------------------------------------------------- internals

    private void onMatch(long firstSampleRt, long clipEndRt, ShazamClient.Result r, long now) {
        double d = r.offsetS * 1000.0 - videoPos(firstSampleRt);
        if (panelTitle != null && !sameTitle(r.title, panelTitle) && recTitle == null) {
            // Shazam disagrees with the panel; ask again before trusting either.
            actions.log("sync_identity_conflict", "shazam", r.title, "panel", panelTitle);
            provisional = false;
            if (pending && sameTitle(r.title, pendingTitle)) {
                actions.log("sync_identity_shazam_confirmed", "title", r.title);
                pending = false;
            } else {
                pending = true;
                pendingTitle = r.title;
                pendingDeltaMs = d;
                scheduleAt(now + CONFIRM_AFTER_CONFLICT);
                return;
            }
        }
        if (recTitle == null || !sameTitle(r.title, recTitle)) {
            recTitle = r.title;
            recArtist = r.artist;
            if (lyricsTitle == null || !sameTitle(r.title, lyricsTitle)) requestLyrics(r.title, r.artist);
        }
        if (!aligned) {
            pending = false;
            accept(d, clipEndRt, "first");
            schedulePeriodic(firstSampleRt);
            return;
        }
        if (Math.abs(d - deltaMs) <= AGREE_MS) {
            accept(d, clipEndRt, pending ? "old_confirmed" : "agree");
            pending = false;
            schedulePeriodic(firstSampleRt);
            return;
        }
        if (pending && Math.abs(d - pendingDeltaMs) <= AGREE_MS) {
            actions.log("sync_realigned", "fromMs", deltaMs, "toMs", d);
            accept(d, clipEndRt, "new_confirmed");
            pending = false;
            scheduleAt(now + RECHECK_AFTER_EDIT);
            return;
        }
        // Conflict (repeated chorus or a real edit): hide and confirm quickly.
        pending = true;
        pendingDeltaMs = d;
        mode = Mode.CONFIRMING;
        actions.log("sync_conflict", "deltaMs", deltaMs, "newMs", d, "timeskew", r.timeskew);
        scheduleAt(now + CONFIRM_AFTER_CONFLICT);
    }

    private String pendingTitle;

    private void accept(double d, long clipEndRt, String why) {
        if (provisional) actions.log("sync_provisional_snap", "deltaMs", d);
        provisional = false;
        agreements = aligned ? agreements + 1 : 1;
        deltaMs = d;
        aligned = true;
        lastAcceptedClipEndRt = clipEndRt;
        mode = Mode.ALIGNED;
        actions.log("sync_accept", "why", why, "deltaMs", d, "agreements", agreements);
    }

    private void schedulePeriodic(long firstSampleRt) {
        scheduleAt(firstSampleRt + CLIP_PERIOD);
    }

    private void scheduleAt(long rt) {
        nextCaptureRt = rt;
    }

    private void startItem(long startRt, long now) {
        newGeneration("new_item", now);
        itemStartRt = startRt;
        joinedMidItem = position > 5_000;          // engine started mid-video
        panelTitle = null;
        panelArtist = null;
        enterAcquiring(joinedMidItem ? now + FIRST_CLIP_AFTER_JOIN : itemStartRt + FIRST_CLIP_AFTER_START, now);
    }

    private void enterAcquiring(long firstClipRt, long now) {
        mode = Mode.ACQUIRING;
        scheduleAt(firstClipRt);
        actions.log("sync_acquiring", "firstClipInMs", firstClipRt - now, "midItem", joinedMidItem);
    }

    private void newGeneration(String why, long now) {
        itemGen++;
        clipEpoch++;
        aligned = false;
        pending = false;
        pendingTitle = null;
        provisional = false;
        agreements = 0;
        captureInFlight = false;
        nextCaptureRt = Long.MAX_VALUE;
        recTitle = null;
        recArtist = null;
        lines = null;
        lyricsFor = null;
        lyricsTitle = null;
        lyricsInFlight = false;
        actions.log("sync_generation", "itemGen", itemGen, "why", why);
    }

    private void prefetchLyrics(String title, String artist) {
        requestLyrics(title, artist);
    }

    private void requestLyrics(String title, String artist) {
        String k = key(title, artist);
        if (k.equals(lyricsFor)) return;           // already loaded or in flight
        lyricsFor = k;
        lyricsTitle = title;
        lyricsInFlight = true;
        lines = null;
        actions.requestLyrics(itemGen, title, artist);
    }

    private double videoPos(long t) {
        if (state != STATE_PLAYING) return position;
        return position + speed * (t - updateRt);
    }

    private static boolean sameTitle(String a, String b) {
        String x = Lrc.norm(a), y = Lrc.norm(b);
        return !x.isEmpty() && (x.equals(y) || x.contains(y) || y.contains(x));
    }

    private static String key(String title, String artist) {
        return Lrc.norm(title) + "|" + Lrc.norm(artist).toLowerCase(Locale.ROOT);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
