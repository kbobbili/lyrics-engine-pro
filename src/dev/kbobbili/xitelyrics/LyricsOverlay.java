package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import dev.kbobbili.xitelyrics.sync.SyncEngine;

import java.util.Objects;

/**
 * Bottom-centred subtitle overlay: the current line over a semi-transparent
 * box, styled by an OverlayStyle preset. An accessibility overlay window, never
 * focusable or touchable, so the remote keeps controlling XITE and XITE is not
 * paused (an activity would make XITE kill itself; a window does not).
 *
 * Line changes crossfade; LyricsController asks the engine for the line
 * LEAD_MS ahead, so each fade-in completes when the line actually starts.
 */
final class LyricsOverlay implements LyricsController.DisplayListener {
    static final int FADE_MS = 120;

    private final Context ctx;
    private final WindowManager wm;
    private final FrameLayout root;
    private final TextView line;
    private final GradientDrawable bg = new GradientDrawable();
    private String shown;
    private boolean added;
    private long noticeUntil;
    private static final long NOTICE_MS = 3_000;
    private int bottomDp = 56;

    LyricsOverlay(Context ctx, OverlayStyle style) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
        line = new TightTextView(ctx);
        line.setTextColor(Color.WHITE);
        line.setGravity(Gravity.CENTER);
        line.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);   // even line lengths
        line.setIncludeFontPadding(false);   // Lexend's built-in top/bottom spacing made the box tall
        line.setBackground(bg);
        // INVISIBLE, never GONE: a GONE view shrinks the window to zero height, and Android
        // then stops redrawing it, so the last line stays on screen after "hiding".
        line.setVisibility(View.INVISIBLE);
        // Full-width window with the box centred in it: a WRAP_CONTENT window is first
        // measured at dialog width, which makes long lines wrap needlessly.
        root = new FrameLayout(ctx);
        root.addView(line, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        apply(style);
    }

    void apply(OverlayStyle s) {
        line.setShadowLayer(s.shadowRadius, 0, 2, Color.BLACK);
        bottomDp = s.bottomDp;
        if (added) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            lp.y = dp(bottomDp);
            wm.updateViewLayout(root, lp);
        }
        line.setAllCaps(s.allCaps);
        line.setMaxLines(s.maxLines);
        line.setMaxWidth((int) (ctx.getResources().getDisplayMetrics().widthPixels * s.maxWidthFraction));
        line.setTypeface(s.typeface(ctx));
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.textSp);
        line.setLetterSpacing(s.letterSpacing);
        setTightLineHeight();
        line.setPadding(dp(s.padHdp), dp(s.padVdp), dp(s.padHdp), dp(s.padVdp));
        bg.setColor(Color.argb(s.boxAlpha, 0, 0, 0));
        bg.setCornerRadius(dp(s.cornerDp));
        Events.log("overlay_style", "name", s.name);
    }

    /** Share of the font's natural gap between wrapped lines that is kept (owner choice, 2026-10-02). */
    private static final float LINE_GAP_KEEP = 0.55f;

    /**
     * The visible gap between two wrapped lines runs from one line's baseline to the top of
     * the next line's capitals. Lexend's natural line height leaves a wide one; keep only
     * LINE_GAP_KEEP of it. Glyph sizes are untouched.
     */
    private void setTightLineHeight() {
        android.graphics.Paint.FontMetrics fm = line.getPaint().getFontMetrics();
        android.graphics.Rect cap = new android.graphics.Rect();
        line.getPaint().getTextBounds("H", 0, 1, cap);
        float natural = fm.descent - fm.ascent, capHeight = cap.height();
        int tight = Math.round(capHeight + LINE_GAP_KEEP * (natural - capHeight));
        line.setLineHeight(tight);
        Events.log("overlay_line_height", "naturalPx", Math.round(natural), "capPx", Math.round(capHeight),
                "linePx", tight);
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    void attach() {
        if (added) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(bottomDp);
        wm.addView(root, lp);
        added = true;
        Events.log("overlay_attached");
    }

    void detach() {
        if (!added) return;
        line.animate().cancel();
        wm.removeView(root);
        added = false;
        shown = null;
        Events.log("overlay_detached");
    }

    @Override
    public void onNotice(String text) {
        if (!added) return;
        noticeUntil = android.os.SystemClock.uptimeMillis() + NOTICE_MS;
        shown = null;   // the next lyric update after the notice redraws from scratch
        line.animate().cancel();
        line.setText(text);
        line.setAlpha(1f);
        line.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDisplay(SyncEngine.Display d) {
        if (!added) return;
        if (android.os.SystemClock.uptimeMillis() < noticeUntil) return;   // a notice is showing
        if (noticeUntil != 0) {
            noticeUntil = 0;
            if (!d.visible) {
                line.animate().alpha(0f).setDuration(FADE_MS)
                        .withEndAction(() -> line.setVisibility(View.INVISIBLE)).start();
                return;
            }
        }
        String next = d.visible ? d.line : null;
        if (Objects.equals(next, shown)) return;
        boolean wasShowing = shown != null;
        shown = next;
        line.animate().cancel();
        if (next == null) {
            line.animate().alpha(0f).setDuration(FADE_MS)
                    .withEndAction(() -> line.setVisibility(View.INVISIBLE)).start();
            return;
        }
        if (wasShowing) {
            // Quick dip: old line out, new line in, FADE_MS in total.
            line.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
                line.setText(next);
                line.animate().alpha(1f).setDuration(FADE_MS / 2).start();
            }).start();
        } else {
            line.setText(next);
            line.setAlpha(0f);
            line.setVisibility(View.VISIBLE);
            line.animate().alpha(1f).setDuration(FADE_MS).start();
        }
    }
}
