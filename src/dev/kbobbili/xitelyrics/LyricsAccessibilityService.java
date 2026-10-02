package dev.kbobbili.xitelyrics;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reads XITE's info panel (title/artist/progress), tracks whether XITE is
 * the active window, and (re)launches ProjectionActivity when capture isn't
 * running. Launching from here relies on Android's background-activity-start
 * exemption for system-bound accessibility services.
 *
 * XITE kills its own process ~0.7 s after any activity pauses it, so the
 * projection is only ever requested from the home screen, never over XITE.
 *
 * Read-only: it never clicks or types anything.
 */
public class LyricsAccessibilityService extends AccessibilityService {
    private static final long POLL_MS = 2000;
    private static final long FAST_POLL_MS = 500;
    private static final long DEBOUNCE_MS = 300;
    private static final long BACKOFF_MIN_MS = 60_000;
    private static final long BACKOFF_MAX_MS = 15 * 60_000;
    /** A consent dialog left open this long counts as abandoned. */
    private static final long PENDING_REQUEST_MS = 120_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable read = this::read;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            read();
            // Poll faster while capture is missing, so a quick pass through the home
            // screen (e.g. waking the TV and opening XITE) is enough to start it.
            handler.postDelayed(this, State.captureRunning ? POLL_MS : FAST_POLL_MS);
        }
    };

    /** Every package with a HOME activity (the TV also lists its setup wizard). */
    private final Set<String> launcherPkgs = new HashSet<>();
    private long lastLaunchRt = -BACKOFF_MAX_MS;
    private int awayReads;
    private View marker;

    static final String ACTION_MARKER = "dev.kbobbili.xitelyrics.MARKER";
    /**
     * Lyrics overlay on/off (persisted, default off):
     *   am broadcast -a dev.kbobbili.xitelyrics.OVERLAY --ez on true -p dev.kbobbili.xitelyrics
     */
    static final String ACTION_OVERLAY = "dev.kbobbili.xitelyrics.OVERLAY";
    /** Shows sample text for 5 s (layout check), whatever the overlay setting. */
    static final String ACTION_PREVIEW = "dev.kbobbili.xitelyrics.OVERLAY_PREVIEW";
    /**
     * Overlay style preset (persisted), see OverlayStyle.PRESETS:
     *   am broadcast -a dev.kbobbili.xitelyrics.STYLE --ei style 2 -p dev.kbobbili.xitelyrics
     */
    static final String ACTION_STYLE = "dev.kbobbili.xitelyrics.STYLE";
    /**
     * Debug: write XITE's accessibility tree to files/ui-dump-N.txt once a second for
     * "seconds" seconds (read-only).
     *   am broadcast -a dev.kbobbili.xitelyrics.DUMP_UI --ei seconds 20 -p dev.kbobbili.xitelyrics
     */
    static final String ACTION_DUMP_UI = "dev.kbobbili.xitelyrics.DUMP_UI";
    /** Debug: run the Star-button action (injected key presses don't reach the key filter). */
    static final String ACTION_STAR_TEST = "dev.kbobbili.xitelyrics.STAR_TEST";
    /** "7 Caption, no box" (Lexend, compact centred block at the bottom, shadow only), owner's pick 2026-10-02. */
    private static final int DEFAULT_STYLE = 6;
    private static final String PREFS = "overlay";
    private LyricsOverlay overlay;

    /**
     * Calibration: flashes a white square (top-left) "count" times, every
     * "periodMs", each lit for "onMs", and logs the elapsed-realtime of the
     * display frame that shows / hides it.
     *   am broadcast -a dev.kbobbili.xitelyrics.MARKER --ei count 20 -p dev.kbobbili.xitelyrics
     */
    private final BroadcastReceiver markerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ACTION_PREVIEW.equals(i.getAction())) {
                previewOverlay(i.getStringExtra("text"), i.getIntExtra("seconds", 5));
                return;
            }
            if (ACTION_STAR_TEST.equals(i.getAction())) {
                starPressed();
                return;
            }
            if (ACTION_DUMP_UI.equals(i.getAction())) {
                int seconds = i.getIntExtra("seconds", 20);
                for (int n = 0; n < seconds; n++) {
                    final int idx = n;
                    handler.postDelayed(() -> dumpUi(idx), n * 1000L);
                }
                return;
            }
            if (ACTION_STYLE.equals(i.getAction())) {
                int idx = i.getIntExtra("style", 0);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("style", idx).apply();
                if (overlay != null) overlay.apply(style());
                return;
            }
            if (ACTION_OVERLAY.equals(i.getAction())) {
                boolean on = i.getBooleanExtra("on", true);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("on", on).apply();
                setOverlay(on);
                return;
            }
            flashMarker(i.getIntExtra("count", 20), i.getIntExtra("periodMs", 2000),
                    i.getIntExtra("onMs", 300));
        }
    };
    private long backoffMs = BACKOFF_MIN_MS;

    @Override
    protected void onServiceConnected() {
        Events.init(this);
        Events.log("a11y_connected");
        State.a11yConnected = true;
        State.consentDenied = false;
        for (ResolveInfo r : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            launcherPkgs.add(r.activityInfo.packageName);
        }
        Events.log("launchers", "pkgs", launcherPkgs.toString());
        IntentFilter f = new IntentFilter(ACTION_MARKER);
        f.addAction(ACTION_OVERLAY);
        f.addAction(ACTION_PREVIEW);
        f.addAction(ACTION_STYLE);
        f.addAction(ACTION_DUMP_UI);
        f.addAction(ACTION_STAR_TEST);
        registerReceiver(markerReceiver, f, RECEIVER_EXPORTED);
        setOverlay(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("on", false));
        handler.post(poll);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // XITE fires many content-changed events per second; coalesce them.
        handler.removeCallbacks(read);
        handler.postDelayed(read, DEBOUNCE_MS);
    }

    private void read() {
        // XITE is "in front" when its window is the topmost application window.
        // (isActive/isFocused flicker on this TV, so they aren't used.)
        Boolean fg = null;
        String top = null;
        Panel panel = new Panel();
        for (AccessibilityWindowInfo w : getWindows()) { // ordered top to bottom
            AccessibilityNodeInfo root = w.getRoot();
            CharSequence pkg = root == null ? null : root.getPackageName();
            boolean isXite = pkg != null && PlaybackWatcher.XITE.contentEquals(pkg);
            if (fg == null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                fg = isXite;
                top = pkg == null ? "?" : pkg.toString();
            }
            if (isXite) collect(root, panel, 0);
        }
        // Some reads on this TV return no application window at all: that is "unknown",
        // not "XITE left". A real departure must also show in two consecutive reads
        // (and XITE kills itself when it leaves, which ends its media session anyway).
        boolean xiteFront;
        if (fg == null) {
            xiteFront = State.xiteForeground;
        } else if (fg) {
            awayReads = 0;
            xiteFront = true;
        } else {
            xiteFront = ++awayReads >= 2 ? false : State.xiteForeground;
        }

        if (xiteFront != State.xiteForeground) {
            State.xiteForeground = xiteFront;
            Events.log("xite_foreground", "value", xiteFront, "topApp", top);
            LyricsController.get(this).onForeground(xiteFront);
        }
        if (panel.title != null || panel.artist != null) {
            if (!Objects.equals(panel.title, State.panelTitle) || !Objects.equals(panel.artist, State.panelArtist)) {
                Events.log("panel", "title", panel.title, "artist", panel.artist, "progress", panel.progress);
            }
            State.panelTitle = panel.title;
            State.panelArtist = panel.artist;
            LyricsController.get(this).onPanel(panel.title, panel.artist, panel.progress);
            State.panelSeenRt = SystemClock.elapsedRealtime();
        }
        if (panel.progress != null && !panel.progress.equals(State.panelProgress)) {
            State.Playback p = State.playback;
            long rt = SystemClock.elapsedRealtime();
            Events.log("panel_progress", "text", panel.progress,
                    "videoPos", p == null ? null : p.positionAt(rt));
            State.panelProgress = panel.progress;
        }
        maybeLaunchProjection(top != null && launcherPkgs.contains(top));
    }

    private void maybeLaunchProjection(boolean onHomeScreen) {
        if (State.captureRunning) {
            backoffMs = BACKOFF_MIN_MS;
            return;
        }
        if (!onHomeScreen || State.consentDenied) return;
        long now = SystemClock.elapsedRealtime();
        long pending = State.projectionRequestRt;
        if (pending != 0 && now - pending < PENDING_REQUEST_MS) return;
        if (now - lastLaunchRt < backoffMs) return;
        lastLaunchRt = now;
        backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
        Events.log("projection_launch_attempt", "nextBackoffMs", backoffMs);
        try {
            startActivity(new Intent(this, ProjectionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(ProjectionActivity.EXTRA_CALLER, "a11y"));
        } catch (RuntimeException e) {
            Events.log("projection_launch_failed", "error", e.toString());
        }
    }

    private static final class Panel {
        String title, artist, progress;
    }

    private void collect(AccessibilityNodeInfo node, Panel panel, int depth) {
        if (node == null || depth > 60) return;
        String id = node.getViewIdResourceName();
        CharSequence text = node.getText();
        if (id != null && text != null && text.length() > 0) {
            if (id.endsWith("channelDetailsSongTitle")) panel.title = text.toString();
            else if (id.endsWith("channelDetailsArtistName")) panel.artist = text.toString();
            else if (id.endsWith("channelDetailsProgressText")) panel.progress = text.toString();
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), panel, depth + 1);
    }

    private OverlayStyle style() {
        return OverlayStyle.get(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("style", DEFAULT_STYLE));
    }

    /** Sample text in the current style, whatever the overlay setting. */
    /** Remote buttons, acted on only while XITE is in front; everything else passes through. */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        if (!State.xiteForeground) return false;
        int code = e.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_GUIDE) {           // TV-icon button
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) LyricsController.get(this).toggleLyrics();
            return true;
        }
        if (code == android.view.KeyEvent.KEYCODE_MACRO_1) {         // Star button
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) starPressed();
            return true;
        }
        return false;
    }

    // XITE's info panel: "Save Video" becomes "Video Saved" once saved (checked 2026-10-02).
    private static final String SAVE_ID = "channelDetailsButtonsLikeVideo";
    private static final String OPEN_PANEL_ID = "openMenuButton";
    private static final String PANEL_ID = "channelDetailsRoot";
    private static final String UNSAVED = "Save Video", SAVED = "Video Saved";
    /** Keep the panel up this long after a save, so XITE's own "Saved to Library" pop-up is readable. */
    private static final long SAVED_POPUP_MS = 8_000;
    private static final long STEP_MS = 50, PANEL_WAIT_MS = 2_000, FOCUS_WAIT_MS = 1_500, CONFIRM_MAX_MS = 3_000;

    /** Polls check every STEP_MS until it returns true or timeoutMs passes, then calls done(ok). */
    private void pollUntil(java.util.function.BooleanSupplier check, long timeoutMs,
            java.util.function.Consumer<Boolean> done) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (check.getAsBoolean()) done.accept(true);
                else if (SystemClock.uptimeMillis() >= deadline) done.accept(false);
                else handler.postDelayed(this, STEP_MS);
            }
        });
    }

    /**
     * Star: save the playing video to the XITE library, as quickly and invisibly as XITE
     * allows. Its Save button only exists in the info panel, so: open the panel, highlight
     * Save, press OK, and close the panel again with Back as soon as XITE confirms.
     * Presses OK only on a focused "Save Video" (never un-saves, never hits another
     * control) and Back only while the panel is confirmed open (never exits XITE).
     */
    private void starPressed() {
        long t0 = SystemClock.uptimeMillis();
        if (findXiteNode(SAVE_ID) != null) {          // owner already has the panel open: leave it open
            saveVia(t0, false);
            return;
        }
        AccessibilityNodeInfo open = findXiteNode(OPEN_PANEL_ID);
        if (open == null || !open.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Events.log("star_save", "result", "no_panel");
            LyricsController.get(this).notice("Couldn't save");
            return;
        }
        pollUntil(() -> findXiteNode(SAVE_ID) != null, PANEL_WAIT_MS, ok -> {
            if (ok) {
                saveVia(t0, true);
            } else {
                Events.log("star_save", "result", "save_button_missing");
                LyricsController.get(this).notice("Couldn't save");
            }
        });
    }

    private void saveVia(long t0, boolean weOpenedPanel) {
        AccessibilityNodeInfo save = findXiteNode(SAVE_ID);
        String label = save == null ? null : String.valueOf(save.getContentDescription());
        if (SAVED.equals(label)) {
            finishStar(t0, weOpenedPanel, "already_saved", "Already saved");
            return;
        }
        if (!UNSAVED.equals(label)) {   // unknown state: never guess
            finishStar(t0, weOpenedPanel, "unknown_label:" + label, "Couldn't save");
            return;
        }
        save.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        pollUntil(() -> {
            AccessibilityNodeInfo n = findXiteNode(SAVE_ID);
            return n != null && n.isFocused() && UNSAVED.contentEquals(String.valueOf(n.getContentDescription()));
        }, FOCUS_WAIT_MS, focused -> {
            if (!focused || !performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) {
                finishStar(t0, weOpenedPanel, "not_focused", "Couldn't save");
                return;
            }
            long pressedAt = SystemClock.uptimeMillis();
            pollUntil(() -> {
                AccessibilityNodeInfo n = findXiteNode(SAVE_ID);
                return n != null && SAVED.contentEquals(String.valueOf(n.getContentDescription()));
            }, CONFIRM_MAX_MS, saved -> {
                Events.log("star_timing", "toPressMs", pressedAt - t0, "toConfirmMs", SystemClock.uptimeMillis() - t0);
                if (saved) {
                    // XITE confirms with its own pop-up; no note of ours. Closing the panel
                    // takes the pop-up with it, so wait before pressing Back.
                    handler.postDelayed(() -> finishStar(t0, weOpenedPanel, "saved", null), SAVED_POPUP_MS);
                } else {
                    finishStar(t0, weOpenedPanel, "unconfirmed", "Couldn't save");
                }
            });
        });
    }

    /** Close the panel we opened (Back, only if it is still up), then confirm on screen. */
    private void finishStar(long t0, boolean weOpenedPanel, String result, String note) {
        boolean closed = false;
        if (weOpenedPanel && findXiteNode(PANEL_ID) != null) closed = performGlobalAction(GLOBAL_ACTION_BACK);
        Events.log("star_save", "result", result, "panelClosed", closed, "totalMs", SystemClock.uptimeMillis() - t0);
        if (note != null) LyricsController.get(this).notice(note);
    }

    /**
     * First XITE node whose view id ends with idSuffix, freshly read (refresh()): Android
     * caches node contents, and a stale "Save Video" on a saved video would un-save it.
     */
    private AccessibilityNodeInfo findXiteNode(String idSuffix) {
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null
                    || !PlaybackWatcher.XITE.contentEquals(root.getPackageName())) continue;
            AccessibilityNodeInfo n = findById(root, idSuffix, 0);
            if (n != null && n.refresh()) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo findById(AccessibilityNodeInfo n, String idSuffix, int depth) {
        if (n == null || depth > 60) return null;
        String id = n.getViewIdResourceName();
        if (id != null && id.endsWith(idSuffix)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findById(n.getChild(i), idSuffix, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Debug dump of every XITE node: text, description, id, flags, bounds, actions. */
    private void dumpUi(int idx) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null
                    || !PlaybackWatcher.XITE.contentEquals(root.getPackageName())) continue;
            sb.append("window type=").append(w.getType()).append(" active=").append(w.isActive()).append('\n');
            dumpNode(root, sb, 0);
        }
        java.io.File f = new java.io.File(getExternalFilesDir(null), "ui-dump-" + idx + ".txt");
        try (java.io.FileWriter out = new java.io.FileWriter(f)) {
            out.write(sb.toString());
        } catch (java.io.IOException ex) {
            Events.log("ui_dump_failed", "error", ex.toString());
        }
        Events.log("ui_dump", "idx", idx, "chars", sb.length(), "panel", sb.indexOf("channelDetails") >= 0);
    }

    private void dumpNode(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 60) return;
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(n.getClassName());
        if (n.getText() != null) sb.append(" text=\"").append(n.getText()).append('"');
        if (n.getContentDescription() != null) sb.append(" desc=\"").append(n.getContentDescription()).append('"');
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        if (n.isClickable()) sb.append(" CLICKABLE");
        if (n.isFocusable()) sb.append(" focusable");
        if (n.isFocused()) sb.append(" FOCUSED");
        if (!n.isVisibleToUser()) sb.append(" hidden");
        sb.append(' ').append(r.toShortString());
        sb.append(" actions=").append(n.getActionList().size()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), sb, depth + 1);
    }

    private void previewOverlay(String text, int seconds) {
        LyricsOverlay o = new LyricsOverlay(this, style());
        o.attach();
        o.onDisplay(dev.kbobbili.xitelyrics.sync.SyncEngine.Display.of(
                text != null ? text : "I've been waiting for the time of my life", null));
        handler.postDelayed(o::detach, seconds * 1000L);
    }

    private void setOverlay(boolean on) {
        if (on) {
            if (overlay == null) overlay = new LyricsOverlay(this, style());
            overlay.attach();
            LyricsController.get(this).setDisplayListener(overlay);
        } else if (overlay != null) {
            LyricsController.get(this).setDisplayListener(null);
            overlay.detach();
        }
        Events.log("overlay_setting", "on", on);
    }

    private void flashMarker(int count, int periodMs, int onMs) {
        WindowManager wm = getSystemService(WindowManager.class);
        if (marker == null) {
            marker = new View(this);
            marker.setBackgroundColor(Color.WHITE);
            marker.setVisibility(View.INVISIBLE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(220, 220,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 40;
            lp.y = 40;
            wm.addView(marker, lp);
        }
        Events.log("marker_start", "count", count, "periodMs", periodMs, "onMs", onMs);
        for (int n = 0; n < count; n++) {
            final int idx = n;
            handler.postDelayed(() -> setMarker(true, idx), 1000L + (long) n * periodMs);
            handler.postDelayed(() -> setMarker(false, idx), 1000L + (long) n * periodMs + onMs);
        }
        handler.postDelayed(() -> {
            if (marker != null) wm.removeView(marker);
            marker = null;
            Events.log("marker_done");
        }, 2000L + (long) count * periodMs);
    }

    private void setMarker(boolean on, int idx) {
        if (marker == null) return;
        marker.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        // The next frame callback's vsync time is the frame that draws the change.
        Choreographer.getInstance().postFrameCallback(frameNs -> {
            long toBoot = SystemClock.elapsedRealtimeNanos() - System.nanoTime();
            Events.log(on ? "marker_on" : "marker_off", "idx", idx, "frameRtNs", frameNs + toBoot);
        });
    }

    @Override
    public boolean onUnbind(Intent intent) {
        State.a11yConnected = false;
        if (overlay != null) overlay.detach();
        Events.log("a11y_unbound");
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
