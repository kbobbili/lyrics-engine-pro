import dev.kbobbili.xitelyrics.fingerprint.SongRecFingerprint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Desktop harness for the Phase 3 gate: runs the Java port on every
 * tools/phase3/fixtures/*.wav and compares with the reference *.sig byte for
 * byte. On a mismatch it decodes both signatures and reports peak-level
 * differences, so float rounding (+-1 magnitude) is told apart from logic bugs.
 *
 * Run: tools/phase3/check.sh
 */
public class FingerprintCheck {
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "tools/phase3/fixtures");
        List<Path> wavs;
        try (Stream<Path> s = Files.list(dir)) {
            wavs = s.filter(p -> p.toString().endsWith(".wav")).sorted().toList();
        }
        int pass = 0;
        for (Path wav : wavs) {
            short[] pcm = readWav(wav);
            byte[] mine = SongRecFingerprint.signature(pcm);
            byte[] ref = Files.readAllBytes(Path.of(wav.toString().replace(".wav", ".sig")));
            String name = wav.getFileName().toString();
            if (java.util.Arrays.equals(mine, ref)) {
                pass++;
                System.out.printf("PASS %-22s %6d bytes%n", name, ref.length);
                continue;
            }
            List<int[]> a = peaks(ref), b = peaks(mine);
            int same = 0, magOnly = 0;
            for (int[] p : a) {
                for (int[] q : b) {
                    if (p[0] == q[0] && p[1] == q[1] && p[3] == q[3]) {
                        if (p[2] == q[2]) same++;
                        else if (Math.abs(p[2] - q[2]) <= 1) magOnly++;
                        break;
                    }
                }
            }
            System.out.printf("FAIL %-22s ref %d bytes / %d peaks, java %d bytes / %d peaks; "
                            + "identical peaks %d, magnitude +-1 only %d, first diff at byte %d%n",
                    name, ref.length, a.size(), mine.length, b.size(), same, magOnly, firstDiff(ref, mine));
        }
        System.out.printf("%d/%d fixtures byte-identical%n", pass, wavs.size());
        if (pass != wavs.size()) System.exit(1);
    }

    static int firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return i;
        return n;
    }

    /** [band, fftPass, magnitude, bin] for every peak in a binary signature. */
    static List<int[]> peaks(byte[] sig) {
        List<int[]> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(sig).order(ByteOrder.LITTLE_ENDIAN);
        b.position(56);
        while (b.remaining() >= 8) {
            int band = b.getInt() - 0x60030040;
            int len = b.getInt();
            int end = b.position() + len;
            int pass = 0;
            while (b.position() < end) {
                int d = b.get() & 0xff;
                if (d == 0xff) {
                    pass = b.getInt();
                    d = b.get() & 0xff;
                }
                pass += d;
                int mag = b.getShort() & 0xffff;
                int bin = b.getShort() & 0xffff;
                out.add(new int[] {band, pass, mag, bin});
            }
            b.position(end + (4 - len % 4) % 4);
        }
        return out;
    }

    static short[] readWav(Path p) throws IOException {
        byte[] d = Files.readAllBytes(p);
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
        throw new IOException("no data chunk in " + p);
    }
}
