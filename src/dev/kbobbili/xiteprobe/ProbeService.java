package dev.kbobbili.xiteprobe;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Probe: logs every piece of text XITE exposes to accessibility, so we can
 * see whether the song title/artist/time are readable. Read-only; it never
 * clicks or types anything.
 *
 * Watch with: adb logcat -s XiteProbe
 */
public class ProbeService extends AccessibilityService {
    private static final String TAG = "XiteProbe";
    private static final long DEBOUNCE_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable snapshot = this::logSnapshot;
    private String lastSnapshot = "";

    private static final long POLL_MS = 2000;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            logSnapshot();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        Log.i(TAG, "connected, watching com.xite.musicvideos");
        // XITE may not emit events while video plays, so also read on a timer.
        handler.post(poll);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // XITE fires many content-changed events per second (progress bar);
        // coalesce them into one tree read.
        handler.removeCallbacks(snapshot);
        handler.postDelayed(snapshot, DEBOUNCE_MS);
    }

    private void logSnapshot() {
        StringBuilder sb = new StringBuilder();
        int xiteWindows = 0;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !pkg.toString().equals("com.xite.musicvideos")) continue;
            xiteWindows++;
            collect(root, sb, 0);
        }
        if (xiteWindows == 0) {
            if (!lastSnapshot.equals("<none>")) Log.i(TAG, "no XITE window visible");
            lastSnapshot = "<none>";
            return;
        }
        if (sb.length() == 0) sb.append("(XITE window has no text nodes)\n");
        String snap = sb.toString();
        // Log only when something other than the ticking clock changed,
        // but always log the clock line so position is visible.
        String withoutTime = snap.replaceAll("\\d{1,2}:\\d{2}", "#:##");
        if (!withoutTime.equals(lastSnapshot)) {
            lastSnapshot = withoutTime;
            Log.i(TAG, "---- screen text changed ----\n" + snap);
        } else {
            for (String line : snap.split("\n")) {
                if (line.matches(".*\\d{1,2}:\\d{2}.*")) Log.i(TAG, "time " + line.trim());
            }
        }
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 60) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0)) {
            sb.append("  ".repeat(Math.min(depth, 20)));
            if (text != null && text.length() > 0) sb.append("text=\"").append(text).append("\" ");
            if (desc != null && desc.length() > 0) sb.append("desc=\"").append(desc).append("\" ");
            String id = node.getViewIdResourceName();
            if (id != null) sb.append("id=").append(id).append(' ');
            sb.append('[').append(node.getClassName()).append("]\n");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), sb, depth + 1);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
