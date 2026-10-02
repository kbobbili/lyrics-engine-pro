import dev.kbobbili.xitelyrics.lyrics.Lrc;
import dev.kbobbili.xitelyrics.lyrics.LrcLibClient;
import dev.kbobbili.xitelyrics.recognition.ShazamClient;
import dev.kbobbili.xitelyrics.sync.SyncEngine;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5 deterministic checks for SyncEngine: scripted playback/panel/
 * recognition sequences on a fake clock. Run: tools/phase5/test.sh
 */
public class EngineTests {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    /** Records the engine's side effects. */
    static final class Fake implements SyncEngine.Actions {
        final List<int[]> captures = new ArrayList<>();
        final List<String> lyricRequests = new ArrayList<>();
        final List<String> log = new ArrayList<>();

        public void requestCapture(int gen, int epoch, int clipMs) { captures.add(new int[] {gen, epoch, clipMs}); }

        public void requestLyrics(int gen, String title, String artist) { lyricRequests.add(gen + ":" + title); }

        public void log(String type, Object... kv) { log.add(type); }

        boolean logged(String t) { return log.contains(t); }
    }

    static final int PLAYING = 3, PAUSED = 2, STOPPED = 1, BUFFERING = 6;
    static final long T0 = 1_000_000;

    // Lyrics: one line every 4 s from 0 s: "L0", "L1", ...; a deliberate gap at 40 s.
    static LrcLibClient.Choice lyrics() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(String.format("[00:%02d.00]L%d%n", i * 4, i));
        sb.append("[00:40.00]\n");
        for (int i = 11; i < 60; i++) sb.append(String.format("[%02d:%02d.00]L%d%n", (i * 4) / 60, (i * 4) % 60, i));
        Constructor<LrcLibClient.Choice> c = LrcLibClient.Choice.class.getDeclaredConstructor(
                LrcLibClient.Status.class, long.class, List.class, String.class);
        c.setAccessible(true);
        return c.newInstance(LrcLibClient.Status.OK, 42L, Lrc.parse(sb.toString()), "");
    }

    static ShazamClient.Result match(String title, double offsetS) throws Exception {
        Constructor<ShazamClient.Result> c = ShazamClient.Result.class.getDeclaredConstructor(ShazamClient.Status.class,
                String.class, String.class, String.class, double.class, double.class, double.class, int.class,
                long.class, int.class, String.class);
        c.setAccessible(true);
        return c.newInstance(ShazamClient.Status.MATCH, title, "Artist", "k", offsetS, 1e-4, 0.0, 200, 100L, 0, null);
    }

    static ShazamClient.Result fail(ShazamClient.Status s, int retryAfter) throws Exception {
        Constructor<ShazamClient.Result> c = ShazamClient.Result.class.getDeclaredConstructor(ShazamClient.Status.class,
                String.class, String.class, String.class, double.class, double.class, double.class, int.class,
                long.class, int.class, String.class);
        c.setAccessible(true);
        return c.newInstance(s, null, null, null, Double.NaN, Double.NaN, Double.NaN, 0, 100L, retryAfter, "x");
    }

    /** Engine with XITE in front, a song started at T0 (position 0) and its panel at +1.5 s. */
    static SyncEngine songStarted(Fake f) {
        SyncEngine e = new SyncEngine(f);
        e.onSession(true, T0 - 5_000);
        e.onForeground(true, T0 - 5_000);
        e.onPlayback(BUFFERING, 0, T0 - 500, 0f, T0 - 500);
        e.onPlayback(PLAYING, 0, T0, 1f, T0);
        e.onPanel("Song", "Artist", T0 + 1_500);
        return e;
    }

    static void runTo(SyncEngine e, long from, long to) {
        for (long t = from; t <= to; t += 100) e.tick(t);
    }

    /** Drive to the first (fast) capture, answer it with a match at video offset +500 ms, load lyrics. */
    static SyncEngine aligned(Fake f) throws Exception {
        SyncEngine e = songStarted(f);
        runTo(e, T0, T0 + 2_000);
        int[] cap = f.captures.get(0);
        e.onRecognition(cap[0], cap[1], T0 + 2_000, T0 + 7_000, match("Song", 2.5), T0 + 7_300);
        e.onLyrics(cap[0], "Song", "Artist", lyrics(), T0 + 7_500);
        return e;
    }

    public static void main(String[] args) throws Exception {
        Fake f = new Fake();
        SyncEngine e = songStarted(f);
        runTo(e, T0, T0 + 1_900);
        check(f.captures.isEmpty(), "no capture before +2 s");
        e.tick(T0 + 2_000);
        check(f.captures.size() == 1 && f.captures.get(0)[2] == 5_000, "fast first capture at +2 s, 5 s long");
        check(f.lyricRequests.size() == 1 && f.lyricRequests.get(0).endsWith("Song"), "lyrics prefetched from the panel");

        f = new Fake();
        e = aligned(f);
        check(e.mode() == SyncEngine.Mode.ALIGNED && Math.abs(e.deltaMs() - 500) < 1e-6, "match aligns: delta 500 ms");
        check(f.lyricRequests.size() == 1, "Shazam title = panel title: no second lyrics fetch");
        SyncEngine.Display d = e.display(T0 + 21_000);   // ref 21.5 s -> L5 (20-24 s)
        check(d.visible && "L5".equals(d.line) && "L6".equals(d.next), "shows current + next line: " + d.line + "/" + d.next);
        runTo(e, T0 + 7_500, T0 + 46_900);
        check(f.captures.size() == 1, "no capture before 45 s after the first clip");
        e.tick(T0 + 47_000);
        check(f.captures.size() == 2 && f.captures.get(1)[2] == 8_000, "periodic capture 45 s after the first clip, 8 s long");

        // Stale result from an old generation is ignored.
        int[] old = f.captures.get(1);
        e.onPlayback(STOPPED, 0, T0 + 58_000, 0f, T0 + 58_000);
        e.onPlayback(BUFFERING, 0, T0 + 58_100, 0f, T0 + 58_100);
        e.onPlayback(PLAYING, 0, T0 + 58_500, 1f, T0 + 58_500);
        e.onRecognition(old[0], old[1], T0 + 57_000, T0 + 65_000, match("Song", 57.5), T0 + 65_300);
        check(f.logged("sync_stale_result") && !e.display(T0 + 66_000).visible, "old-generation result after a new item is dropped");

        // Pause freezes the line, drops in-flight clips; resume re-measures in 3 s.
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);            // second capture in flight
        int[] inflight = f.captures.get(1);
        long pauseAt = T0 + 58_000;                     // video 58 s, ref 58.5 -> L14 (56-60)
        e.onPlayback(PAUSED, 58_000, pauseAt, 0f, pauseAt);
        SyncEngine.Display frozen = e.display(pauseAt + 30_000);
        check(e.mode() == SyncEngine.Mode.PAUSED && frozen.visible && "L14".equals(frozen.line), "pause freezes the current line");
        e.onRecognition(inflight[0], inflight[1], T0 + 57_000, T0 + 65_000, match("Song", 57.5), pauseAt + 1_000);
        check(f.logged("sync_stale_result"), "clip spanning the pause is dropped");
        long resumeAt = pauseAt + 40_000;
        e.onPlayback(PLAYING, 58_007, resumeAt, 1f, resumeAt);
        check(e.mode() == SyncEngine.Mode.ALIGNED && e.display(resumeAt + 100).visible, "resume keeps alignment");
        runTo(e, resumeAt, resumeAt + 2_900);
        int before = f.captures.size();
        e.tick(resumeAt + 3_000);
        check(f.captures.size() == before + 1, "re-measure 3 s after resume");

        // Buffering hides.
        f = new Fake();
        e = aligned(f);
        e.onPlayback(BUFFERING, 30_000, T0 + 30_000, 0f, T0 + 30_000);
        check(!e.display(T0 + 30_500).visible, "buffering hides the lyrics");

        // Seek: new generation, reacquire 2 s later without the intro delay.
        f = new Fake();
        e = aligned(f);
        e.onPlayback(PLAYING, 90_000, T0 + 30_000, 1f, T0 + 30_000);   // jumped from ~30 s to 90 s
        check(!e.display(T0 + 30_100).visible && e.mode() == SyncEngine.Mode.ACQUIRING, "seek hides and reacquires");
        before = f.captures.size();
        runTo(e, T0 + 30_000, T0 + 32_000);
        check(f.captures.size() == before + 1, "capture 2 s after a seek");

        // XITE position correction: delta rebased, displayed reference continuous.
        f = new Fake();
        e = aligned(f);
        long t = T0 + 150_000;
        double refBefore = e.deltaMs() + 150_000;      // videoPos(t) = 150 000 before correction
        e.onPlayback(PLAYING, 149_880, t, 1f, t);       // XITE says 120 ms less
        double refAfter = e.deltaMs() + 149_880;
        check(Math.abs(refBefore - refAfter) < 1e-6 && f.logged("sync_rebase"), "position correction rebases delta (no jump)");

        // Miss keeps lyrics; retry after 15 s.
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);
        int[] c2 = f.captures.get(1);
        e.onRecognition(c2[0], c2[1], T0 + 57_000, T0 + 65_000, fail(ShazamClient.Status.ERROR, 0), T0 + 65_300);
        check(e.display(T0 + 66_000).visible, "a network error keeps the lyrics");
        before = f.captures.size();
        runTo(e, T0 + 65_300, T0 + 80_200);
        check(f.captures.size() == before, "no retry before 15 s after a network error");
        e.tick(T0 + 80_300);
        check(f.captures.size() == before + 1, "retry 15 s after a network error");

        // No match while aligned = a scene/dialogue: hide, poll every 3 s, realign when music is back.
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);
        c2 = f.captures.get(1);
        e.onRecognition(c2[0], c2[1], T0 + 47_000, T0 + 55_000, fail(ShazamClient.Status.NO_MATCH, 0), T0 + 55_300);
        check(!e.display(T0 + 55_400).visible && f.logged("sync_music_lost"), "no match while aligned hides the lyrics");
        before = f.captures.size();
        runTo(e, T0 + 55_300, T0 + 58_300);
        check(f.captures.size() == before + 1 && f.captures.get(before)[2] == 5_000, "polls with a 5 s clip 3 s later");
        int[] c4 = f.captures.get(before);
        // Music back after a 30 s scene: video 58.3 s now maps to song 28.8 s (delta -29.5 s).
        e.onRecognition(c4[0], c4[1], T0 + 58_300, T0 + 63_300, match("Song", 28.8), T0 + 63_600);
        check(e.mode() == SyncEngine.Mode.ALIGNED && Math.abs(e.deltaMs() + 29_500) < 1 && e.display(T0 + 64_000).visible,
                "first match after the scene realigns directly (no confirmation needed)");

        // Freshness cap: no accepted clip for > 120 s hides.
        f = new Fake();
        e = aligned(f);
        e.tick(T0 + 7_000 + 120_001);
        check(!e.display(T0 + 127_100).visible && f.logged("sync_expired"), "alignment expires 120 s after the last accepted clip");

        // Rate limit: wait max(Retry-After, 60 s).
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);
        c2 = f.captures.get(1);
        e.onRecognition(c2[0], c2[1], T0 + 57_000, T0 + 65_000, fail(ShazamClient.Status.RATE_LIMITED, 90), T0 + 65_000);
        before = f.captures.size();
        runTo(e, T0 + 65_000, T0 + 70_000);
        check(e.display(T0 + 70_000).visible, "429 keeps valid lyrics visible");
        runTo(e, T0 + 70_000, T0 + 65_000 + 89_900);
        check(f.captures.size() == before, "429: no capture before Retry-After (90 s)");

        // Conflict (repeated chorus): hide, confirm in 5 s; old alignment confirmed -> restored.
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);
        c2 = f.captures.get(1);
        e.onRecognition(c2[0], c2[1], T0 + 57_000, T0 + 65_000, match("Song", 67.5), T0 + 65_300); // +10 s
        check(e.mode() == SyncEngine.Mode.CONFIRMING && !e.display(T0 + 65_500).visible, "conflicting match hides and confirms");
        runTo(e, T0 + 65_300, T0 + 70_300);
        int[] c3 = f.captures.get(f.captures.size() - 1);
        e.onRecognition(c3[0], c3[1], T0 + 70_300, T0 + 78_300, match("Song", 70.8), T0 + 78_600);   // old delta
        check(e.mode() == SyncEngine.Mode.ALIGNED && Math.abs(e.deltaMs() - 500) < 1, "old alignment confirmed -> restored");

        // Conflict confirmed twice -> realign (real edit).
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);
        c2 = f.captures.get(1);
        e.onRecognition(c2[0], c2[1], T0 + 57_000, T0 + 65_000, match("Song", 54.5), T0 + 65_300);   // -2.5 s edit
        runTo(e, T0 + 65_300, T0 + 70_300);
        c3 = f.captures.get(f.captures.size() - 1);
        e.onRecognition(c3[0], c3[1], T0 + 70_300, T0 + 78_300, match("Song", 67.8), T0 + 78_600);
        check(e.mode() == SyncEngine.Mode.ALIGNED && Math.abs(e.deltaMs() + 2_500) < 1, "edit confirmed by 2 clips -> realigned to -2500 ms");
        before = f.captures.size();
        runTo(e, T0 + 78_600, T0 + 93_600);
        check(f.captures.size() == before + 1, "re-check 15 s after a confirmed edit (edits cluster)");

        // Ad: no panel by +3 s -> AD, no Shazam calls.
        f = new Fake();
        e = new SyncEngine(f);
        e.onSession(true, T0 - 5_000);
        e.onForeground(true, T0 - 5_000);
        e.onPlayback(BUFFERING, 0, T0 - 500, 0f, T0 - 500);
        e.onPlayback(PLAYING, 0, T0, 1f, T0);
        runTo(e, T0, T0 + 2_900);
        check(f.captures.isEmpty(), "no capture at +2 s while the panel hasn't appeared");
        runTo(e, T0 + 2_900, T0 + 30_000);
        check(e.mode() == SyncEngine.Mode.AD && f.captures.isEmpty(), "no panel within 3 s -> ad, no captures");
        e.onPanel("Late", "Panel", T0 + 30_000);
        runTo(e, T0 + 30_000, T0 + 32_000);
        check(f.captures.size() == 1, "late panel reclassifies as song and captures");

        // Mid-song startup (no panel): capture after 2 s, not treated as an ad.
        f = new Fake();
        e = new SyncEngine(f);
        e.onSession(true, T0);
        e.onForeground(true, T0);
        e.onPlayback(PLAYING, 60_000, T0, 1f, T0);
        runTo(e, T0, T0 + 2_000);
        check(f.captures.size() == 1 && e.mode() == SyncEngine.Mode.ACQUIRING, "mid-song startup captures after 2 s");
        runTo(e, T0 + 2_000, T0 + 10_000);
        check(e.mode() != SyncEngine.Mode.AD, "mid-song startup is not classified as an ad");

        // Foreground lost: hide, new generation, in-flight result dropped.
        f = new Fake();
        e = aligned(f);
        runTo(e, T0 + 7_500, T0 + 47_000);
        c2 = f.captures.get(1);
        e.onForeground(false, T0 + 58_000);
        check(!e.display(T0 + 58_100).visible, "XITE leaving the front hides");
        e.onRecognition(c2[0], c2[1], T0 + 57_000, T0 + 65_000, match("Song", 57.5), T0 + 65_000);
        check(f.logged("sync_stale_result"), "result for the pre-background generation dropped");

        // Session replaced: new generation.
        f = new Fake();
        e = aligned(f);
        int gen = e.itemGen();
        e.onSession(false, T0 + 30_000);
        e.onSession(true, T0 + 30_100);
        check(e.itemGen() > gen && !e.display(T0 + 30_200).visible, "session replacement starts a new generation");

        // Identity: Shazam disagrees with the panel -> confirm; same answer twice -> trust Shazam.
        f = new Fake();
        e = songStarted(f);
        runTo(e, T0, T0 + 12_000);
        int[] c1 = f.captures.get(0);
        e.onRecognition(c1[0], c1[1], T0 + 12_000, T0 + 20_000, match("Other", 12.5), T0 + 20_300);
        check(e.mode() == SyncEngine.Mode.ACQUIRING && f.logged("sync_identity_conflict"), "Shazam vs panel conflict not trusted yet");
        runTo(e, T0 + 20_300, T0 + 25_300);
        c2 = f.captures.get(f.captures.size() - 1);
        e.onRecognition(c2[0], c2[1], T0 + 25_300, T0 + 33_300, match("Other", 25.8), T0 + 33_600);
        check(e.mode() == SyncEngine.Mode.ALIGNED && f.lyricRequests.get(f.lyricRequests.size() - 1).endsWith("Other"),
                "same Shazam identity twice -> trusted, lyrics fetched for it");

        // Lyrics for a superseded identity are ignored.
        f = new Fake();
        e = aligned(f);
        e.onLyrics(e.itemGen(), "Something Else", "X", lyrics(), T0 + 21_000);
        check(e.display(T0 + 21_000).visible, "lyrics for another identity ignored");

        // Deliberate gap / long hold hides.
        f = new Fake();
        e = aligned(f);
        check(!e.display(T0 + 40_000).visible, "blank LRC line (instrumental gap) hides");

        // Fast start: provisional lines from the panel identity before the first match.
        f = new Fake();
        e = songStarted(f);
        runTo(e, T0, T0 + 2_000);
        e.onLyrics(e.itemGen(), "Song", "Artist", lyrics(), T0 + 2_500);
        SyncEngine.Display p = e.display(T0 + 5_000);              // video 5.0 s, delta 0 -> L1 (4-8 s)
        check(p.visible && "L1".equals(p.line) && f.logged("sync_provisional"), "provisional line shown before any match");
        int[] fc = f.captures.get(0);
        e.onRecognition(fc[0], fc[1], T0 + 2_000, T0 + 7_000, match("Song", 2.9), T0 + 7_300);   // true delta +900
        SyncEngine.Display snapped = e.display(T0 + 7_400);       // video 7.4 + 0.9 = 8.3 -> L2
        check(snapped.visible && "L2".equals(snapped.line) && f.logged("sync_provisional_snap"), "first match snaps to the measured delta");

        // Provisional gives up after 30 s without a match.
        f = new Fake();
        e = songStarted(f);
        e.onLyrics(e.itemGen(), "Song", "Artist", lyrics(), T0 + 2_500);
        e.tick(T0 + 30_001);
        check(!e.display(T0 + 30_100).visible && f.logged("sync_provisional_expired"), "unconfirmed provisional lyrics hide after 30 s");

        // A miss while acquiring retries at once.
        f = new Fake();
        e = songStarted(f);
        runTo(e, T0, T0 + 2_000);
        fc = f.captures.get(0);
        e.onRecognition(fc[0], fc[1], T0 + 2_000, T0 + 7_000, fail(ShazamClient.Status.NO_MATCH, 0), T0 + 7_300);
        runTo(e, T0 + 7_300, T0 + 7_800);
        check(f.captures.size() == 2 && f.captures.get(1)[2] == 5_000, "miss while acquiring -> next 5 s clip at once");

        // Shazam names a different song: provisional lyrics hide.
        f = new Fake();
        e = songStarted(f);
        runTo(e, T0, T0 + 2_000);
        e.onLyrics(e.itemGen(), "Song", "Artist", lyrics(), T0 + 2_500);
        fc = f.captures.get(0);
        e.onRecognition(fc[0], fc[1], T0 + 2_000, T0 + 7_000, match("Other", 2.5), T0 + 7_300);
        check(!e.display(T0 + 7_400).visible, "identity conflict hides provisional lyrics");

        // Joining mid-video: no provisional guess (position may not match the song).
        f = new Fake();
        e = new SyncEngine(f);
        e.onSession(true, T0);
        e.onForeground(true, T0);
        e.onPlayback(PLAYING, 60_000, T0, 1f, T0);
        e.onPanel("Song", "Artist", T0 + 500);
        e.onLyrics(e.itemGen(), "Song", "Artist", lyrics(), T0 + 1_000);
        check(!e.display(T0 + 1_500).visible, "no provisional lyrics when joining mid-video");

        // Panel disappearing (no more panel events) changes nothing.
        f = new Fake();
        e = aligned(f);
        check(e.display(T0 + 50_000).visible, "panel disappearance keeps lyrics");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures != 0) System.exit(1);
    }
}
