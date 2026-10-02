package dev.kbobbili.xitelyrics;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Every package with a HOME activity (the TV also lists its setup wizard). */
    private final Set<String> launcherPkgs = new HashSet<>();
    private long lastLaunchRt = -BACKOFF_MAX_MS;
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
        boolean xiteFront = fg != null && fg;

        if (xiteFront != State.xiteForeground) {
            State.xiteForeground = xiteFront;
            Events.log("xite_foreground", "value", xiteFront, "topApp", top);
        }
        if (panel.title != null || panel.artist != null) {
            if (!Objects.equals(panel.title, State.panelTitle) || !Objects.equals(panel.artist, State.panelArtist)) {
                Events.log("panel", "title", panel.title, "artist", panel.artist, "progress", panel.progress);
            }
            State.panelTitle = panel.title;
            State.panelArtist = panel.artist;
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

    @Override
    public boolean onUnbind(Intent intent) {
        State.a11yConnected = false;
        Events.log("a11y_unbound");
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
