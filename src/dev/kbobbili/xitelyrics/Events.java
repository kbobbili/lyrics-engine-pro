package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Append-only event log: every playback, panel, projection and capture
 * transition goes to logcat (tag XiteLyrics) and to events.jsonl in the
 * app's external files dir, so spike runs can be analysed offline.
 *
 * Times: "rt" is SystemClock.elapsedRealtime() in ms, the same clock as
 * PlaybackState.getLastPositionUpdateTime().
 */
public final class Events {
    public static final String TAG = "XiteLyrics";
    private static File file;

    private Events() {}

    public static synchronized void init(Context ctx) {
        if (file != null) return;
        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) file = new File(dir, "events.jsonl");
    }

    /** Logs an event; kv is alternating key, value pairs. */
    public static synchronized void log(String type, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("rt", SystemClock.elapsedRealtime());
            o.put("wall", System.currentTimeMillis());
            o.put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                Object v = kv[i + 1];
                if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) v = null;
                o.put(String.valueOf(kv[i]), v == null ? JSONObject.NULL : v);
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad event " + type, e);
        }
        String line = o.toString();
        Log.i(TAG, line);
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(line);
            w.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "events.jsonl write failed", e);
        }
    }
}
