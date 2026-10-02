package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.os.Build;

import dev.kbobbili.xitelyrics.lyrics.LrcLibClient;
import dev.kbobbili.xitelyrics.recognition.ShazamClient;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Phase 4 on-device check: the fixture music clips go through the TV path
 * (Java fingerprint -> ShazamClient -> LrcLibClient) on the TV's network.
 * Results are logged as p4_result events for comparison with the desktop run.
 *   am broadcast -a dev.kbobbili.xitelyrics.P4_SELFTEST -p dev.kbobbili.xitelyrics
 */
final class ClientSelfTest {
    static final String ACTION = "dev.kbobbili.xitelyrics.P4_SELFTEST";

    private ClientSelfTest() {}

    static String userAgent() {
        return "Dalvik/2.1.0 (Linux; U; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL + ")";
    }

    static void run(Context ctx) {
        new Thread(() -> {
            File dir = new File(ctx.getExternalFilesDir(null), "fixtures");
            File[] wavs = dir.listFiles((d, n) -> n.startsWith("music") && n.endsWith("s.wav"));
            if (wavs == null) return;
            Arrays.sort(wavs);
            ShazamClient shazam = new ShazamClient(userAgent());
            LrcLibClient lrclib = new LrcLibClient(new File(ctx.getCacheDir(), "lrclib"), userAgent());
            for (File wav : wavs) {
                try {
                    short[] pcm = readWav(Files.readAllBytes(wav.toPath()));
                    ShazamClient.Result r = shazam.recognize(pcm);
                    String lyrics = null;
                    long id = 0;
                    int lines = 0;
                    if (r.status == ShazamClient.Status.MATCH) {
                        LrcLibClient.Choice c = lrclib.choose(r.title, r.artist);
                        lyrics = c.status.name();
                        id = c.candidateId;
                        lines = c.lines.size();
                    }
                    Events.log("p4_result", "name", wav.getName(), "status", r.status.name(), "title", r.title,
                            "artist", r.artist, "offset", r.offsetS, "latencyMs", r.latencyMs,
                            "lyrics", lyrics, "lrclibId", id, "lines", lines, "error", r.error);
                    Thread.sleep(5_000);
                } catch (Exception e) {
                    Events.log("p4_result", "name", wav.getName(), "error", e.toString());
                }
            }
            Events.log("p4_done");
        }, "p4-selftest").start();
    }

    private static short[] readWav(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        short[] s = new short[(d.length - 44) / 2];
        b.position(44);
        b.asShortBuffer().get(s);
        return s;
    }
}
