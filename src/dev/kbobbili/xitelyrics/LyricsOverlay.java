package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.kbobbili.xitelyrics.sync.SyncEngine;

import java.util.Objects;

/**
 * Bottom-centred subtitle overlay: current line bold over a semi-transparent
 * backing, next line dimmed below it. An accessibility overlay window, never
 * focusable or touchable, so the remote keeps controlling XITE and XITE is not
 * paused (an activity would make XITE kill itself; a window does not).
 */
final class LyricsOverlay implements LyricsController.DisplayListener {
    private final WindowManager wm;
    private final FrameLayout root;
    private final LinearLayout box;
    private final TextView current, next;
    private String shownLine, shownNext;
    private boolean added;

    LyricsOverlay(Context ctx) {
        wm = ctx.getSystemService(WindowManager.class);
        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(ctx, 14);
        box.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(150, 0, 0, 0));
        bg.setCornerRadius(dp(ctx, 12));
        box.setBackground(bg);

        int maxW = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.85);
        current = text(ctx, 30, Color.WHITE, Typeface.BOLD, maxW);
        next = text(ctx, 22, Color.argb(170, 255, 255, 255), Typeface.NORMAL, maxW);
        box.addView(current);
        box.addView(next);
        box.setVisibility(View.GONE);
        // Full-width window with the box centred in it: a WRAP_CONTENT window is first
        // measured at dialog width, which makes long lines wrap needlessly.
        root = new FrameLayout(ctx);
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
    }

    private static TextView text(Context ctx, int sp, int color, int style, int maxWidth) {
        TextView t = new TextView(ctx);
        t.setMaxWidth(maxWidth);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(4, 0, 2, Color.BLACK);
        t.setMaxLines(2);
        return t;
    }

    private static int dp(Context ctx, int v) {
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
        lp.y = dp(box.getContext(), 56);   // above XITE's own bottom-edge UI
        wm.addView(root, lp);
        added = true;
        Events.log("overlay_attached");
    }

    void detach() {
        if (!added) return;
        wm.removeView(root);
        added = false;
        shownLine = shownNext = null;
        Events.log("overlay_detached");
    }

    @Override
    public void onDisplay(SyncEngine.Display d) {
        if (!added) return;
        String line = d.visible ? d.line : null;
        String nxt = d.visible ? d.next : null;
        if (Objects.equals(line, shownLine) && Objects.equals(nxt, shownNext)) return;
        shownLine = line;
        shownNext = nxt;
        if (line == null) {
            box.setVisibility(View.GONE);
            return;
        }
        current.setText(line);
        next.setText(nxt == null ? "" : nxt);
        next.setVisibility(nxt == null ? View.GONE : View.VISIBLE);
        box.setVisibility(View.VISIBLE);
    }
}
