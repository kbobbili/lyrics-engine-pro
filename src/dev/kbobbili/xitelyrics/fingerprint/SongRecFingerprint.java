package dev.kbobbili.xitelyrics.fingerprint;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Shazam audio signature from 16 kHz mono 16-bit PCM: a Java port of the
 * SongRec-derived fingerprinting in shazamio-core 1.1.2
 * (src/fingerprinting/algorithm.rs and signature_format.rs).
 *
 * Origins and licences: SongRec (marin-m, GPL-3.0) reverse-engineered the
 * Shazam signature; shazamio-core (dotX12, MIT) carries the implementation
 * ported here; the FFT mirrors chfft 0.3.4 (MPL-2.0). Personal use.
 *
 * Unlike shazamio-core's make_signature_from_bytes, this does NOT cut long
 * input to a centred 10 s segment: callers pass exactly the clip they want
 * (keep it <= 10 s; Shazam's offset refers to the first sample passed).
 */
public final class SongRecFingerprint {
    private static final String DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,";
    private static final int SAMPLE_RATE = 16000;
    private static final int[] FORMER_FFTS = {1, 3, 6};
    private static final int[] FREQ_NEIGHBOURS = {-10, -7, -4, -3, 1, 2, 5, 8};
    private static final int[] TIME_NEIGHBOURS = {
            -53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249};

    private final short[] ring = new short[2048];
    private int ringIndex;
    private final float[] reordered = new float[2048];
    private final float[][] fftOutputs = new float[256][1025];
    private int fftOutputsIndex;
    private final float[][] spreadOutputs = new float[256][1025];
    private int spreadOutputsIndex;
    private int numSpreadFftsDone;
    private final RealFft2048 fft = new RealFft2048();
    private final float[] binRe = new float[1025];
    private final float[] binIm = new float[1025];
    private final float[] spreadCopy = new float[1025];

    /** Peaks per frequency band 0..3, in discovery order. */
    @SuppressWarnings("unchecked")
    private final List<int[]>[] bands = new List[] {
            new ArrayList<int[]>(), new ArrayList<int[]>(), new ArrayList<int[]>(), new ArrayList<int[]>()};

    private SongRecFingerprint() {}

    /** Binary signature for the whole buffer (trailing samples short of 128 are ignored, as upstream). */
    public static byte[] signature(short[] pcm) {
        SongRecFingerprint g = new SongRecFingerprint();
        short[] chunk = new short[128];
        for (int off = 0; off + 128 <= pcm.length; off += 128) {
            System.arraycopy(pcm, off, chunk, 0, 128);
            g.doFft(chunk);
            g.doPeakSpreading();
            g.numSpreadFftsDone++;
            if (g.numSpreadFftsDone >= 46) g.doPeakRecognition();
        }
        return g.encode(pcm.length);
    }

    /** The form Shazam's API expects in signature.uri. */
    public static String dataUri(short[] pcm) {
        return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(signature(pcm));
    }

    private void doFft(short[] chunk) {
        System.arraycopy(chunk, 0, ring, ringIndex, 128);
        ringIndex = (ringIndex + 128) & 2047;
        float[] window = HanningWindow.MULTIPLIERS;
        for (int i = 0; i < 2048; i++) {
            reordered[i] = (float) ring[(i + ringIndex) & 2047] * window[i];
        }
        fft.forward(reordered, binRe, binIm);
        float[] out = fftOutputs[fftOutputsIndex];
        for (int i = 0; i <= 1024; i++) {
            float re = binRe[i], im = binIm[i];
            out[i] = Math.max((re * re + im * im) / (float) (1 << 17), 0.0000000001f);
        }
        fftOutputsIndex = (fftOutputsIndex + 1) & 255;
    }

    private void doPeakSpreading() {
        float[] real = fftOutputs[(fftOutputsIndex - 1) & 255];
        float[] spread = spreadOutputs[spreadOutputsIndex];
        System.arraycopy(real, 0, spread, 0, 1025);
        for (int p = 0; p <= 1022; p++) {
            spread[p] = Math.max(Math.max(spread[p], spread[p + 1]), spread[p + 2]);
        }
        System.arraycopy(spread, 0, spreadCopy, 0, 1025);
        for (int p = 0; p <= 1024; p++) {
            for (int former : FORMER_FFTS) {
                float[] f = spreadOutputs[(spreadOutputsIndex - former) & 255];
                f[p] = Math.max(f[p], spreadCopy[p]);
            }
        }
        spreadOutputsIndex = (spreadOutputsIndex + 1) & 255;
    }

    private static float magnitude(float v) {
        return Math.max((float) Math.log(v), 1.0f / 64.0f) * 1477.3f + 6144.0f;
    }

    /** Rust's saturating float -> u16 cast. */
    private static int toU16(float v) {
        if (!(v > 0)) return 0;
        return v >= 65535f ? 65535 : (int) v;
    }

    private void doPeakRecognition() {
        float[] m46 = fftOutputs[(fftOutputsIndex - 46) & 255];
        float[] m49 = spreadOutputs[(spreadOutputsIndex - 49) & 255];
        for (int bin = 10; bin <= 1014; bin++) {
            float v = m46[bin];
            if (!(v >= 1.0f / 64.0f && v >= m49[bin - 1])) continue;
            float maxNeighbour = 0.0f;
            for (int off : FREQ_NEIGHBOURS) maxNeighbour = Math.max(maxNeighbour, m49[bin + off]);
            if (!(v > maxNeighbour)) continue;
            float maxOther = maxNeighbour;
            for (int off : TIME_NEIGHBOURS) {
                maxOther = Math.max(maxOther, spreadOutputs[(spreadOutputsIndex + off) & 255][bin - 1]);
            }
            if (!(v > maxOther)) continue;

            int fftPass = numSpreadFftsDone - 46;
            float mag = magnitude(v);
            float before = magnitude(m46[bin - 1]);
            float after = magnitude(m46[bin + 1]);
            float var1 = mag * 2.0f - before - after;
            float var2 = (after - before) * 32.0f / var1;
            int corrected = (bin * 64 + (int) var2) & 0xFFFF;   // i32 -> u16 wraps
            float hz = (float) corrected * (16000.0f / 2.0f / 1024.0f / 64.0f);
            int band;
            int ihz = (int) hz;
            if (ihz >= 250 && ihz <= 519) band = 0;
            else if (ihz >= 520 && ihz <= 1449) band = 1;
            else if (ihz >= 1450 && ihz <= 3499) band = 2;
            else if (ihz >= 3500 && ihz <= 5500) band = 3;
            else continue;
            bands[band].add(new int[] {fftPass, toU16(mag), corrected});
        }
    }

    private byte[] encode(int numberSamples) {
        ByteArrayOutputStream peaksAll = new ByteArrayOutputStream();
        for (int band = 0; band < 4; band++) {
            List<int[]> peaks = bands[band];
            if (peaks.isEmpty()) continue;   // upstream only has entries for bands with peaks
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            int pass = 0;
            for (int[] pk : peaks) {
                if (pk[0] - pass >= 255) {
                    p.write(0xff);
                    writeU32(p, pk[0]);
                    pass = pk[0];
                }
                p.write(pk[0] - pass);
                writeU16(p, pk[1]);
                writeU16(p, pk[2]);
                pass = pk[0];
            }
            byte[] pb = p.toByteArray();
            writeU32(peaksAll, 0x60030040 + band);
            writeU32(peaksAll, pb.length);
            peaksAll.write(pb, 0, pb.length);
            for (int i = 0; i < (4 - pb.length % 4) % 4; i++) peaksAll.write(0);
        }
        byte[] body = peaksAll.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(56 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        int sizeMinusHeader = 56 + body.length - 48;
        b.putInt(0xcafe2580);                       // magic1
        b.putInt(0);                                // crc32, filled below
        b.putInt(sizeMinusHeader);
        b.putInt(0x94119c00);                       // magic2
        b.putInt(0).putInt(0).putInt(0);            // void1
        b.putInt(3 << 27);                          // 16 kHz sample-rate id
        b.putInt(0).putInt(0);                      // void2
        b.putInt(numberSamples + (int) ((float) SAMPLE_RATE * 0.24f));
        b.putInt((15 << 19) + 0x40000);             // fixed value
        b.putInt(0x40000000);
        b.putInt(sizeMinusHeader);
        b.put(body);
        byte[] out = b.array();
        CRC32 crc = new CRC32();
        crc.update(out, 8, out.length - 8);
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, (int) crc.getValue());
        return out;
    }

    private static void writeU32(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
        o.write(v >>> 24);
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >>> 8);
    }
}
