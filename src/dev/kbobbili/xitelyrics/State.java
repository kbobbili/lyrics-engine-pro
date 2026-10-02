package dev.kbobbili.xitelyrics;

import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Process-wide state shared by the services. All services run on the main
 * thread except the capture worker, which only reads; fields are volatile
 * and snapshots are immutable.
 */
public final class State {
    private State() {}

    /** Bumped whenever playback continuity breaks (pause, seek, session/song change). */
    public static volatile int generation;

    public static volatile Playback playback;

    public static volatile boolean xiteForeground;
    public static volatile String panelTitle;
    public static volatile String panelArtist;
    public static volatile String panelProgress;
    public static volatile long panelSeenRt;

    public static volatile boolean a11yConnected;
    public static volatile boolean captureRunning;
    /** Set when the user cancels the consent dialog; stops auto-relaunch until reconnect. */
    public static volatile boolean consentDenied;
    public static volatile long projectionRequestRt;

    public static synchronized int bumpGeneration(String reason) {
        generation++;
        Events.log("generation", "gen", generation, "reason", reason);
        return generation;
    }

    /** Immutable copy of one PlaybackState plus when we saw it. */
    public static final class Playback {
        public final int state;
        public final long position;
        public final long updateRt;
        public final float speed;
        public final long seenRt;

        public Playback(PlaybackState s) {
            state = s.getState();
            position = s.getPosition();
            updateRt = s.getLastPositionUpdateTime();
            speed = s.getPlaybackSpeed();
            seenRt = SystemClock.elapsedRealtime();
        }

        public boolean playing() {
            return state == PlaybackState.STATE_PLAYING;
        }

        /** Video position at elapsed-realtime rt (ms); only meaningful while playing. */
        public double positionAt(double rtMs) {
            if (!playing()) return position;
            return position + speed * (rtMs - updateRt);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("position", position);
            o.put("updateRt", updateRt);
            o.put("speed", (double) speed);
            o.put("seenRt", seenRt);
            return o;
        }
    }

    public static JSONObject snapshot() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("rt", SystemClock.elapsedRealtime());
        o.put("gen", generation);
        Playback p = playback;
        o.put("playback", p == null ? JSONObject.NULL : p.toJson());
        if (p != null && p.playing()) o.put("videoPosNow", p.positionAt(SystemClock.elapsedRealtime()));
        o.put("xiteForeground", xiteForeground);
        o.put("panelTitle", panelTitle == null ? JSONObject.NULL : panelTitle);
        o.put("panelArtist", panelArtist == null ? JSONObject.NULL : panelArtist);
        o.put("panelProgress", panelProgress == null ? JSONObject.NULL : panelProgress);
        o.put("panelSeenRt", panelSeenRt);
        return o;
    }
}
