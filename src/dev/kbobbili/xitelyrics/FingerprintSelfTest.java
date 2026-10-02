package dev.kbobbili.xitelyrics;

import android.content.Context;

import dev.kbobbili.xitelyrics.fingerprint.SongRecFingerprint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Phase 3 on-device gate: fingerprints every fixture WAV pushed to
 * files/fixtures/ and compares with the reference .sig byte for byte, on
 * Android's runtime (whose Math.cos/log could differ from desktop Java).
 * Writes each device-made signature as <name>.tv.sig for the recognition check.
 *
 *   am broadcast -a dev.kbobbili.xitelyrics.FP_SELFTEST -p dev.kbobbili.xitelyrics   (creates the dir)
 *   adb push tools/phase3/fixtures/. /sdcard/Android/data/dev.kbobbili.xitelyrics/files/fixtures/
 *   am broadcast -a dev.kbobbili.xitelyrics.FP_SELFTEST -p dev.kbobbili.xitelyrics
 */
final class FingerprintSelfTest {
    static final String ACTION = "dev.kbobbili.xitelyrics.FP_SELFTEST";

    private FingerprintSelfTest() {}

    static void run(Context ctx) {
        new Thread(() -> {
            File dir = new File(ctx.getExternalFilesDir(null), "fixtures");
            dir.mkdirs(); // adb may not create directories here on Android 14; it can fill them
            File[] wavs = dir.listFiles((d, n) -> n.endsWith(".wav"));
            if (wavs == null || wavs.length == 0) {
                Events.log("fp_selftest", "error", "no fixtures in " + dir);
                return;
            }
            Arrays.sort(wavs);
            int pass = 0;
            for (File wav : wavs) {
                try {
                    short[] pcm = readWav(Files.readAllBytes(wav.toPath()));
                    long t0 = System.nanoTime();
                    byte[] mine = SongRecFingerprint.signature(pcm);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    String base = wav.getName().replace(".wav", "");
                    byte[] ref = Files.readAllBytes(new File(dir, base + ".sig").toPath());
                    boolean same = Arrays.equals(mine, ref);
                    if (same) pass++;
                    try (FileOutputStream o = new FileOutputStream(new File(dir, base + ".tv.sig"))) {
                        o.write(mine);
                    }
                    Events.log("fp_fixture", "name", base, "identical", same, "bytes", mine.length, "ms", ms);
                } catch (IOException e) {
                    Events.log("fp_fixture", "name", wav.getName(), "error", e.toString());
                }
            }
            Events.log("fp_selftest", "pass", pass, "total", wavs.length);
        }, "fp-selftest").start();
    }

    private static short[] readWav(byte[] d) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        while (pos + 8 <= d.length) {
            String id = new String(d, pos, 4, "US-ASCII");
            int size = b.getInt(pos + 4);
            if (id.equals("data")) {
                short[] s = new short[size / 2];
                b.position(pos + 8);
                b.asShortBuffer().get(s);
                return s;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("no data chunk");
    }
}
