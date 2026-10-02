package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.text.Layout;
import android.widget.TextView;

/**
 * A TextView whose width hugs its widest wrapped line. A plain TextView that wraps
 * keeps the full max width, which leaves the caption box wider than the text.
 */
final class TightTextView extends TextView {
    TightTextView(Context ctx) {
        super(ctx);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() < 2) return;
        float widest = 0;
        for (int i = 0; i < layout.getLineCount(); i++) widest = Math.max(widest, layout.getLineWidth(i));
        // +2 px slack so the re-layout at this width never wraps differently.
        int tight = (int) Math.ceil(widest) + 2 + getCompoundPaddingLeft() + getCompoundPaddingRight();
        if (tight < getMeasuredWidth()) {
            // Lay the text out again at the tight width; only shrinking the measured size
            // leaves the old, wider layout, which draws off-centre and gets clipped.
            super.onMeasure(MeasureSpec.makeMeasureSpec(tight, MeasureSpec.EXACTLY), heightMeasureSpec);
        }
    }
}
