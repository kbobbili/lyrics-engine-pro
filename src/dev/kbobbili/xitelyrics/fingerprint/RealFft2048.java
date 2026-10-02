package dev.kbobbili.xitelyrics.fingerprint;

/**
 * 2048-point real FFT that reproduces chfft 0.3.4's RFft1D&lt;f32&gt;::forward
 * operation for operation (single precision, same twiddles, same butterfly
 * order), so fingerprints can match the reference byte for byte.
 *
 * chfft path for n = 2048: pack pairs into 1024 complex values, an in-place
 * mixed-radix FFT (1024 = 4^5, five radix-4 passes), then the real-input
 * post-processing step. Not thread-safe: one instance per generator.
 */
final class RealFft2048 {
    private static final int N = 2048;
    private static final int M = N >> 1;        // complex FFT length
    private static final int HLEN = (N + 1) >> 1;
    private static final int QLEN = (N + 3) >> 2;

    private final float[] omegaRe = new float[M + 1];
    private final float[] omegaIm = new float[M + 1];
    private final float[] coefRe = new float[M];
    private final float[] coefIm = new float[M];
    private final int[] idsInplace;
    private final float[] wRe = new float[M];
    private final float[] wIm = new float[M];

    RealFft2048() {
        calcOmega(M, omegaRe, omegaIm);
        float[] o2Re = new float[N + 1];
        float[] o2Im = new float[N + 1];
        calcOmega(N, o2Re, o2Im);
        for (int i = 0; i < M; i++) {
            // (1 + i*w) * 0.5, with num_complex's multiply: i*w = (0*re - 1*im, 0*im + 1*re)
            float iwRe = 0.0f * o2Re[i] - 1.0f * o2Im[i];
            float iwIm = 0.0f * o2Im[i] + 1.0f * o2Re[i];
            coefRe[i] = (1.0f + iwRe) * 0.5f;
            coefIm[i] = (0.0f + iwIm) * 0.5f;
        }
        idsInplace = bitreverseInplace(bitreverse());
    }

    /** chfft precompute_utils::calc_omega for len divisible by 4. */
    private static void calcOmega(int len, float[] re, float[] im) {
        re[0] = 1.0f;
        im[0] = 0.0f;
        int q = len >> 2;
        int h = len >> 1;
        for (int i = 1; i < q; i++) {
            float theta = ((-2.0f * (float) Math.PI) / (float) len) * (float) i;
            re[i] = (float) Math.cos(theta);
            im[i] = (float) Math.sin(theta);
        }
        for (int i = q; i < h; i++) {
            re[i] = im[i - q];
            im[i] = -re[i - q];
        }
        for (int i = h; i < len; i++) {
            re[i] = -re[i - h];
            im[i] = -im[i - h];
        }
        re[len] = 1.0f;
        im[len] = 0.0f;
    }

    /** chfft calc_bitreverse(1024, [4 x 5]). */
    private static int[] bitreverse() {
        int[] ids = new int[M];
        int size = 1;
        int llen = 1;
        for (int c = 0; c < 5; c++) {
            for (int k = 0; k < llen; k++) ids[k] *= 4;
            for (int i = 1; i < 4; i++) {
                for (int j = 0; j < llen; j++) ids[size++] = ids[j] + i;
            }
            llen *= 4;
        }
        return ids;
    }

    /** chfft calc_bitreverse2inplace: the swap sequence that applies the permutation in place. */
    private static int[] bitreverseInplace(int[] source) {
        int n = source.length;
        int[] nums = new int[n];
        for (int i = 0; i < n; i++) nums[i] = i;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int r = i;
            while (nums[r] != source[i]) r++;
            int t = nums[i];
            nums[i] = nums[r];
            nums[r] = t;
            out[i] = r;
        }
        return out;
    }

    /**
     * Forward transform of 2048 reals; writes 1025 complex bins.
     */
    void forward(float[] src, float[] outRe, float[] outIm) {
        for (int i = 0; i < M; i++) {
            wRe[i] = src[i << 1] * 1.0f;   // .scale(one()) in chfft
            wIm[i] = src[(i << 1) + 1] * 1.0f;
        }
        for (int i = 0; i < M; i++) {
            int s = idsInplace[i];
            if (i != s) {
                float t = wRe[i]; wRe[i] = wRe[s]; wRe[s] = t;
                t = wIm[i]; wIm[i] = wIm[s]; wIm[s] = t;
            }
        }
        radix4Passes();

        for (int i = 1; i < QLEN; i++) {
            int k = HLEN - i;
            // d = work[i] - conj(work[k])
            float dRe = wRe[i] - wRe[k];
            float dIm = wIm[i] - (-wIm[k]);
            // x = coef[i] * d
            float xRe = coefRe[i] * dRe - coefIm[i] * dIm;
            float xIm = coefRe[i] * dIm + coefIm[i] * dRe;
            outRe[i] = wRe[i] - xRe;
            outIm[i] = wIm[i] - xIm;
            outRe[k] = wRe[k] + xRe;
            outIm[k] = wIm[k] + (-xIm);
        }
        outRe[0] = wRe[0] + wIm[0];
        outIm[0] = 0.0f;
        outRe[QLEN] = wRe[QLEN];
        outIm[QLEN] = -wIm[QLEN];
        outRe[HLEN] = wRe[0] - wIm[0];
        outIm[HLEN] = 0.0f;
    }

    /** chfft mixed_kernel_radix4 with quarter_turn(z) = (-z.im, z.re), count = 5. */
    private void radix4Passes() {
        int po2 = 1;
        int rad = M;
        for (int c = 0; c < 5; c++) {
            int po2m = po2;
            po2 <<= 2;
            rad >>= 2;
            for (int j0 = 0; j0 < po2m; j0++) {
                int wpos = rad * j0;
                float w1r = omegaRe[wpos], w1i = omegaIm[wpos];
                float w2r = omegaRe[wpos << 1], w2i = omegaIm[wpos << 1];
                float w3r = omegaRe[wpos * 3], w3i = omegaIm[wpos * 3];
                for (int j = j0; j < M; j += po2) {
                    int p1 = j + po2m, p2 = p1 + po2m, p3 = p2 + po2m;
                    float aR = wRe[j], aI = wIm[j];
                    float cR = wRe[p1] * w1r - wIm[p1] * w1i;
                    float cI = wRe[p1] * w1i + wIm[p1] * w1r;
                    float bR = wRe[p2] * w2r - wIm[p2] * w2i;
                    float bI = wRe[p2] * w2i + wIm[p2] * w2r;
                    float dR = wRe[p3] * w3r - wIm[p3] * w3i;
                    float dI = wRe[p3] * w3i + wIm[p3] * w3r;
                    float abR = aR + bR, abI = aI + bI;
                    float ambR = aR - bR, ambI = aI - bI;
                    float cdR = cR + dR, cdI = cI + dI;
                    float cmdR = cR - dR, cmdI = cI - dI;
                    float tR = -cmdI, tI = cmdR;   // quarter_turn(c - d)
                    wRe[j] = abR + cdR;
                    wIm[j] = abI + cdI;
                    wRe[p1] = ambR - tR;
                    wIm[p1] = ambI - tI;
                    wRe[p2] = abR - cdR;
                    wIm[p2] = abI - cdI;
                    wRe[p3] = ambR + tR;
                    wIm[p3] = ambI + tI;
                }
            }
        }
    }
}
