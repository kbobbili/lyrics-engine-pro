package dev.kbobbili.xitelyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * Holds the MediaProjection and records 8 s clips of XITE's audio only
 * (uid-matched playback capture), 16 kHz mono PCM. Each clip is saved as a
 * WAV plus a JSON sidecar with per-read AudioRecord timestamps and the
 * playback/panel state before and after, so the timing can be analysed.
 *
 * Clips land in /sdcard/Android/data/dev.kbobbili.xitelyrics/files/clips/.
 * Extra clip on demand:
 *   am broadcast -a dev.kbobbili.xitelyrics.CAPTURE_NOW -p dev.kbobbili.xitelyrics
 * Continuous recording (Phase 2): one unbroken AudioRecord written as 60 s
 * chunks under files/rec/<session>/, auto clips paused while it runs. The
 * setting persists, so recording resumes whenever the projection comes back:
 *   am broadcast -a dev.kbobbili.xitelyrics.RECORD --ez on true -p dev.kbobbili.xitelyrics
 * Stop the projection (same path as a system revocation), for lifecycle tests:
 *   am broadcast -a dev.kbobbili.xitelyrics.STOP_PROJECTION -p dev.kbobbili.xitelyrics
 */
public class CaptureService extends Service {
    static final String ACTION_START = "dev.kbobbili.xitelyrics.START";
    static final String ACTION_CAPTURE_NOW = "dev.kbobbili.xitelyrics.CAPTURE_NOW";
    static final String ACTION_STOP_PROJECTION = "dev.kbobbili.xitelyrics.STOP_PROJECTION";
    static final String ACTION_RECORD = "dev.kbobbili.xitelyrics.RECORD";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    /** Also run as a microphone-type service; only if uid capture turns out silent without it. */
    static final String EXTRA_MIC = "withMic";

    private static final int RATE = 16000;
    private static final int CLIP_MS = 8000;
    private static final int READ_FRAMES = RATE / 10;
    private static final int KEEP_CLIPS = 60;
    private static final short SILENT_PEAK = 64;
    private static final int CHUNK_FRAMES = RATE * 60;
    private static final long REC_BUDGET_BYTES = 4L << 30;
    private static final String PREFS = "capture";
    private static final String PREF_CONTINUOUS = "continuous";
    private static final String CHANNEL = "capture";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private HandlerThread worker;
    private Handler work;
    private int xiteUid;
    private int clipSeq;
    private boolean receiverOn;
    private volatile boolean recording;
    private Thread recThread;
    private static volatile CaptureService current;

    /** One engine clip: PCM plus the elapsed-realtime (ms) of its first and past-last sample. */
    public static final class Clip {
        public final short[] pcm;
        public final long firstSampleRt;
        public final long endRt;

        Clip(short[] pcm, long firstSampleRt, long endRt) {
            this.pcm = pcm;
            this.firstSampleRt = firstSampleRt;
            this.endRt = endRt;
        }
    }

    public interface ClipCallback {
        /** Called on the capture worker; clip is null if recording failed. */
        void onClip(Clip clip);
    }

    /** The service holding a live projection, or null. */
    static CaptureService running() {
        CaptureService c = current;
        return c != null && c.projection != null ? c : null;
    }

    /**
     * Records one clip on the worker and hands it to cb (also on the worker, so
     * recognition can run there too and clips never overlap).
     */
    void recordClip(int ms, ClipCallback cb) {
        Handler w = work;
        if (w == null) {
            cb.onClip(null);
            return;
        }
        w.post(() -> cb.onClip(recordClipNow(ms)));
    }

    private Clip recordClipNow(int ms) {
        MediaProjection mp = projection;
        if (mp == null) return null;
        short[] pcm = new short[RATE * ms / 1000];
        AudioRecord rec = null;
        try {
            rec = newRecorder(mp);
            AudioTimestamp ts = new AudioTimestamp();
            double[] t0 = new double[pcm.length / READ_FRAMES + 2];
            int nTs = 0;
            int got = 0;
            rec.startRecording();
            while (got < pcm.length) {
                int n = rec.read(pcm, got, Math.min(READ_FRAMES, pcm.length - got));
                if (n < 0) {
                    Events.log("clip_read_error", "code", n);
                    return null;
                }
                got += n;
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS && nTs < t0.length) {
                    // rt of stream frame 0, assuming frame 0 = timestamp frame 0 (Phase 1/2)
                    t0[nTs++] = ts.nanoTime / 1e6 - ts.framePosition * 1000.0 / RATE;
                }
            }
            if (nTs == 0) return null;
            double[] sorted = Arrays.copyOf(t0, nTs);
            Arrays.sort(sorted);
            long first = Math.round(sorted[nTs / 2]);
            return new Clip(pcm, first, first + ms);
        } catch (Exception e) {
            Events.log("clip_failed", "error", e.toString());
            return null;
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (IllegalStateException ignored) {
                }
                rec.release();
            }
        }
    }

    private final BroadcastReceiver captureNow = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ACTION_RECORD.equals(i.getAction())) {
                boolean on = i.getBooleanExtra("on", true);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_CONTINUOUS, on).apply();
                if (on) startRecording();
                else stopRecording();
            } else if (ACTION_STOP_PROJECTION.equals(i.getAction())) {
                MediaProjection mp = projection;
                Events.log("projection_stop_requested");
                if (mp != null) mp.stop(); // fires onStop like a revocation
            } else if (work != null) {
                work.post(() -> captureClip("manual"));
            }
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Events.log("projection_stopped");
            shutdown();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Events.init(this);
        if (intent == null) {
            // Restarted by the system without a token; a projection can't be resumed.
            Events.log("capture_restart_without_token");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CAPTURE_NOW.equals(intent.getAction())) {
            if (work != null) work.post(() -> captureClip("manual"));
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (projection != null) {
            Events.log("capture_start_ignored", "why", "already_running");
            return START_NOT_STICKY;
        }

        boolean mic = intent.getBooleanExtra(EXTRA_MIC, false);
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                | (mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        // Android 14: the mediaProjection-type service must be in the foreground
        // before getMediaProjection() is called.
        startForeground(1, notification(), type);

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        try {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(code, data);
            xiteUid = getPackageManager().getApplicationInfo(PlaybackWatcher.XITE, 0).uid;
        } catch (Exception e) {
            Events.log("capture_start_failed", "error", e.toString());
            shutdown();
            return START_NOT_STICKY;
        }
        if (projection == null) {
            Events.log("capture_start_failed", "error", "null projection");
            shutdown();
            return START_NOT_STICKY;
        }
        projection.registerCallback(projectionCallback, main);
        State.captureRunning = true;
        IntentFilter filter = new IntentFilter(ACTION_CAPTURE_NOW);
        filter.addAction(ACTION_STOP_PROJECTION);
        filter.addAction(ACTION_RECORD);
        registerReceiver(captureNow, filter, RECEIVER_EXPORTED);
        receiverOn = true;
        worker = new HandlerThread("capture");
        worker.start();
        work = new Handler(worker.getLooper());
        current = this;
        Events.log("projection_started", "xiteUid", xiteUid, "withMic", mic);
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_CONTINUOUS, false)) startRecording();
        return START_NOT_STICKY;
    }

    private AudioRecord newRecorder(MediaProjection mp) {
        AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUid(xiteUid).build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        return new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min * 4, RATE * 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
    }

    private synchronized void startRecording() {
        if (recording || projection == null) return;
        recording = true;
        recThread = new Thread(this::recordLoop, "rec");
        recThread.start();
    }

    private void stopRecording() {
        Thread t;
        synchronized (this) {
            recording = false;
            t = recThread;
            recThread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Records until stopped or the projection ends. Frame numbers in the chunk
     * JSON run on across chunks, so a session is one continuous timeline.
     */
    private void recordLoop() {
        MediaProjection mp = projection;
        if (mp == null) {
            recording = false;
            return;
        }
        String session = String.valueOf(System.currentTimeMillis());
        File dir = new File(getExternalFilesDir(null), "rec/" + session);
        dir.mkdirs();
        AudioRecord rec;
        try {
            rec = newRecorder(mp);
            rec.startRecording();
        } catch (Exception e) {
            Events.log("rec_failed", "error", e.toString());
            recording = false;
            return;
        }
        Events.log("rec_started", "session", session, "bufferFrames", rec.getBufferSizeInFrames());
        short[] buf = new short[CHUNK_FRAMES];
        AudioTimestamp ts = new AudioTimestamp();
        JSONArray reads = new JSONArray();
        long frameBase = 0;
        int got = 0;
        int chunk = 0;
        try {
            while (recording) {
                int n = rec.read(buf, got, Math.min(READ_FRAMES, buf.length - got));
                long now = SystemClock.elapsedRealtimeNanos();
                if (n < 0) {
                    Events.log("rec_read_error", "code", n);
                    break;
                }
                got += n;
                JSONObject r = new JSONObject();
                r.put("frames", frameBase + got);
                r.put("nowNs", now);
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    r.put("tsFrame", ts.framePosition);
                    r.put("tsNs", ts.nanoTime);
                }
                reads.put(r);
                if (got == buf.length) {
                    writeChunk(dir, session, ++chunk, frameBase, buf, got, reads);
                    frameBase += got;
                    got = 0;
                    reads = new JSONArray();
                }
            }
            if (got > 0) writeChunk(dir, session, ++chunk, frameBase, buf, got, reads);
        } catch (JSONException e) {
            Events.log("rec_failed", "error", e.toString());
        } finally {
            rec.stop();
            rec.release();
            recording = false;
            Events.log("rec_stopped", "session", session, "chunks", chunk);
        }
    }

    private void writeChunk(File dir, String session, int chunk, long firstFrame,
            short[] pcm, int frames, JSONArray reads) {
        int peak = 0;
        for (int i = 0; i < frames; i++) peak = Math.max(peak, Math.abs((int) pcm[i]));
        String base = String.format(Locale.US, "chunk-%05d", chunk);
        try {
            JSONObject meta = new JSONObject();
            meta.put("session", session);
            meta.put("chunk", chunk);
            meta.put("rate", RATE);
            meta.put("firstFrame", firstFrame);
            meta.put("frames", frames);
            meta.put("peak", peak);
            meta.put("reads", reads);
            writeWav(new File(dir, base + ".wav"), pcm, frames);
            try (FileOutputStream out = new FileOutputStream(new File(dir, base + ".json"))) {
                out.write(meta.toString().getBytes("UTF-8"));
            }
        } catch (IOException | JSONException e) {
            Events.log("chunk_save_failed", "error", e.toString());
        }
        Events.log("chunk", "session", session, "chunk", chunk, "peak", peak);
        pruneRecordings(new File(getExternalFilesDir(null), "rec"));
    }

    /** Deletes the oldest sessions once recordings exceed the storage budget. */
    private static void pruneRecordings(File root) {
        File[] sessions = root.listFiles(File::isDirectory);
        if (sessions == null) return;
        Arrays.sort(sessions);
        long total = 0;
        for (File s : sessions) {
            File[] fs = s.listFiles();
            if (fs != null) for (File f : fs) total += f.length();
        }
        for (File s : sessions) {
            if (total <= REC_BUDGET_BYTES) return;
            File[] fs = s.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    total -= f.length();
                    f.delete();
                }
            }
            s.delete();
        }
    }

    private void captureClip(String reason) {
        MediaProjection mp = projection;
        if (mp == null) return;
        int seq = ++clipSeq;
        JSONObject meta = new JSONObject();
        JSONArray reads = new JSONArray();
        short[] pcm = new short[RATE * CLIP_MS / 1000];
        int got = 0;
        AudioRecord rec = null;
        try {
            meta.put("seq", seq);
            meta.put("reason", reason);
            meta.put("rate", RATE);
            meta.put("before", State.snapshot());
            rec = newRecorder(mp);
            meta.put("bufferFrames", rec.getBufferSizeInFrames());

            AudioTimestamp ts = new AudioTimestamp();
            meta.put("startCallNs", SystemClock.elapsedRealtimeNanos());
            rec.startRecording();
            meta.put("startedNs", SystemClock.elapsedRealtimeNanos());
            meta.put("recordingState", rec.getRecordingState());
            while (got < pcm.length) {
                int n = rec.read(pcm, got, Math.min(READ_FRAMES, pcm.length - got));
                long now = SystemClock.elapsedRealtimeNanos();
                if (n < 0) {
                    meta.put("readError", n);
                    break;
                }
                got += n;
                JSONObject r = new JSONObject();
                r.put("frames", got);
                r.put("nowNs", now);
                int st = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME);
                if (st == AudioRecord.SUCCESS) {
                    r.put("tsFrame", ts.framePosition);
                    r.put("tsNs", ts.nanoTime);
                } else {
                    r.put("tsErr", st);
                }
                reads.put(r);
            }
            rec.stop();
            meta.put("after", State.snapshot());
        } catch (Exception e) {
            Events.log("clip_failed", "seq", seq, "error", e.toString());
            if (rec != null) rec.release();
            return;
        }
        rec.release();

        int peak = 0;
        double sumSq = 0;
        for (int i = 0; i < got; i++) {
            int v = Math.abs((int) pcm[i]);
            if (v > peak) peak = v;
            sumSq += (double) pcm[i] * pcm[i];
        }
        double rms = got == 0 ? 0 : Math.sqrt(sumSq / got);
        double rmsDb = rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
        boolean silent = peak < SILENT_PEAK;

        String base = String.format(Locale.US, "clip-%04d-%d", seq, System.currentTimeMillis());
        File dir = new File(getExternalFilesDir(null), "clips");
        try {
            meta.put("frames", got);
            meta.put("peak", peak);
            meta.put("rmsDb", Math.round(rmsDb * 10) / 10.0);
            meta.put("silent", silent);
            meta.put("reads", reads);
            dir.mkdirs();
            writeWav(new File(dir, base + ".wav"), pcm, got);
            try (FileOutputStream out = new FileOutputStream(new File(dir, base + ".json"))) {
                out.write(meta.toString(1).getBytes("UTF-8"));
            }
            prune(dir);
        } catch (IOException | JSONException e) {
            Events.log("clip_save_failed", "seq", seq, "error", e.toString());
        }
        int genBefore = meta.optJSONObject("before") == null ? -1 : meta.optJSONObject("before").optInt("gen");
        Events.log("clip", "seq", seq, "reason", reason, "file", base, "frames", got,
                "peak", peak, "rmsDb", Math.round(rmsDb * 10) / 10.0, "silent", silent,
                "genStart", genBefore, "genEnd", State.generation);
    }

    private static void writeWav(File f, short[] pcm, int frames) throws IOException {
        int dataBytes = frames * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + dataBytes).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(dataBytes);
        for (int i = 0; i < frames; i++) b.putShort(pcm[i]);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
    }

    private static void prune(File dir) {
        File[] wavs = dir.listFiles((d, n) -> n.endsWith(".wav"));
        if (wavs == null || wavs.length <= KEEP_CLIPS) return;
        Arrays.sort(wavs);
        for (int i = 0; i < wavs.length - KEEP_CLIPS; i++) {
            String n = wavs[i].getName();
            wavs[i].delete();
            new File(dir, n.replace(".wav", ".json")).delete();
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Lyrics timing",
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentTitle("XITE Lyrics")
                .setContentText("Listening to XITE to time lyrics")
                .setOngoing(true)
                .build();
    }

    private void shutdown() {
        State.captureRunning = false;
        if (current == this) current = null;
        stopRecording();
        if (worker != null) worker.quitSafely();
        worker = null;
        work = null;
        if (receiverOn) unregisterReceiver(captureNow);
        receiverOn = false;
        MediaProjection mp = projection;
        projection = null;
        if (mp != null) {
            mp.unregisterCallback(projectionCallback);
            mp.stop();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Events.log("capture_destroyed");
        if (projection != null) shutdown();
        State.captureRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
