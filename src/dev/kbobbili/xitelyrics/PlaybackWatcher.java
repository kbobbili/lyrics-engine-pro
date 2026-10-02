package dev.kbobbili.xitelyrics;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Tracks XITE's media session. A notification listener is only used because
 * it is what lets us call getActiveSessions(); notifications are ignored.
 *
 * Grant: cmd notification allow_listener dev.kbobbili.xitelyrics/dev.kbobbili.xitelyrics.PlaybackWatcher
 */
public class PlaybackWatcher extends NotificationListenerService {
    static final String XITE = "com.xite.musicvideos";
    /** Grace period for the accessibility service to bind on its own before forcing it. */
    private static final long REBIND_AFTER_MS = 10_000;
    /** Reported vs extrapolated position gap that counts as a seek/song reset. */
    private static final long JUMP_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private MediaSession.Token token;

    static final String ACTION_TRANSPORT = "dev.kbobbili.xitelyrics.TRANSPORT";

    /**
     * Calibration helper: pause/resume XITE through its media session.
     *   am broadcast -a dev.kbobbili.xitelyrics.TRANSPORT --es cmd pause -p dev.kbobbili.xitelyrics
     */
    private final BroadcastReceiver transport = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (FingerprintSelfTest.ACTION.equals(i.getAction())) {
                FingerprintSelfTest.run(PlaybackWatcher.this);
                return;
            }
            String cmd = i.getStringExtra("cmd");
            Events.log("transport", "cmd", cmd, "attached", controller != null);
            if (controller == null) return;
            if ("pause".equals(cmd)) controller.getTransportControls().pause();
            else if ("play".equals(cmd)) controller.getTransportControls().play();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::onSessions;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            if (s != null) onState(s);
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            Events.log("metadata", "keys", m == null ? 0 : m.keySet().size(),
                    "title", m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE));
        }

        @Override
        public void onSessionDestroyed() {
            Events.log("session_destroyed");
            detach();
            State.playback = null;
            State.bumpGeneration("session_destroyed");
        }
    };

    @Override
    public void onListenerConnected() {
        Events.init(this);
        Events.log("listener_connected");
        msm = getSystemService(MediaSessionManager.class);
        IntentFilter debug = new IntentFilter(ACTION_TRANSPORT);
        debug.addAction(FingerprintSelfTest.ACTION);
        registerReceiver(transport, debug, RECEIVER_EXPORTED);
        ComponentName me = new ComponentName(this, PlaybackWatcher.class);
        msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
        onSessions(msm.getActiveSessions(me));
        handler.postDelayed(() -> A11yRebinder.rebindIfNeeded(this), REBIND_AFTER_MS);
    }

    @Override
    public void onListenerDisconnected() {
        Events.log("listener_disconnected");
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        unregisterReceiver(transport);
        detach();
    }

    private void onSessions(List<MediaController> controllers) {
        MediaController xite = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (XITE.equals(c.getPackageName())) {
                    xite = c;
                    break;
                }
            }
        }
        MediaSession.Token t = xite == null ? null : xite.getSessionToken();
        if (t == null ? token == null : t.equals(token)) return;
        detach();
        if (xite == null) {
            Events.log("session_gone");
            State.playback = null;
            State.bumpGeneration("session_gone");
            return;
        }
        controller = xite;
        token = t;
        controller.registerCallback(callback, handler);
        Events.log("session_attached", "tag", controller.getTag());
        State.bumpGeneration("session_attached");
        PlaybackState s = controller.getPlaybackState();
        if (s != null) onState(s);
    }

    private void detach() {
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        token = null;
    }

    private void onState(PlaybackState s) {
        State.Playback prev = State.playback;
        State.Playback now = new State.Playback(s);
        State.playback = now;
        long rt = SystemClock.elapsedRealtime();
        String reason = null;
        if (prev == null) {
            reason = "first_state";
        } else if (prev.state != now.state) {
            reason = "state_" + prev.state + "_to_" + now.state;
        } else if (now.playing()) {
            double expected = prev.positionAt(now.updateRt);
            if (Math.abs(now.position - expected) > JUMP_MS) reason = "position_jump";
        }
        Events.log("playback", "state", now.state, "position", now.position,
                "updateRt", now.updateRt, "speed", (double) now.speed,
                "lagMs", rt - now.updateRt,
                "expected", prev == null ? null : prev.positionAt(now.updateRt));
        if (reason != null) State.bumpGeneration(reason);
    }
}
