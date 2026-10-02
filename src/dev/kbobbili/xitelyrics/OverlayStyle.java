package dev.kbobbili.xitelyrics;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Visual presets for the lyrics overlay (single line over a dark box).
 * Fonts are bundled under assets/fonts (SIL Open Font License).
 */
final class OverlayStyle {
    final String name;
    final String fontAsset;   // null = system Roboto
    final int weight;         // for variable fonts / system bold
    final int textSp;
    final int boxAlpha;       // 0-255 black
    final int cornerDp;       // 999 = pill
    final int padHdp, padVdp;
    final float letterSpacing;
    final boolean allCaps;
    final float maxWidthFraction;   // of screen width; wraps into a compact block when small
    final int maxLines;
    final int bottomDp;             // gap between the box and the bottom of the screen

    private OverlayStyle(String name, String fontAsset, int weight, int textSp, int boxAlpha, int cornerDp,
            int padHdp, int padVdp, float letterSpacing) {
        this(name, fontAsset, weight, textSp, boxAlpha, cornerDp, padHdp, padVdp, letterSpacing, false, 0.85f, 2, 56);
    }

    private OverlayStyle(String name, String fontAsset, int weight, int textSp, int boxAlpha, int cornerDp,
            int padHdp, int padVdp, float letterSpacing, boolean allCaps, float maxWidthFraction, int maxLines,
            int bottomDp) {
        this.bottomDp = bottomDp;
        this.allCaps = allCaps;
        this.maxWidthFraction = maxWidthFraction;
        this.maxLines = maxLines;
        this.name = name;
        this.fontAsset = fontAsset;
        this.weight = weight;
        this.textSp = textSp;
        this.boxAlpha = boxAlpha;
        this.cornerDp = cornerDp;
        this.padHdp = padHdp;
        this.padVdp = padVdp;
        this.letterSpacing = letterSpacing;
    }

    static final OverlayStyle[] PRESETS = {
            new OverlayStyle("1 Classic", null, 700, 30, 150, 12, 28, 14, 0f),
            new OverlayStyle("2 Pill", "fonts/Poppins-SemiBold.ttf", 600, 29, 130, 999, 30, 10, 0f),
            new OverlayStyle("3 Soft", "fonts/Nunito-Variable.ttf", 800, 32, 110, 22, 28, 12, 0f),
            new OverlayStyle("4 Crisp", "fonts/Montserrat-Variable.ttf", 700, 28, 170, 6, 26, 12, 0.02f),
            new OverlayStyle("5 Clean", "fonts/Lexend-Variable.ttf", 500, 31, 140, 16, 28, 12, 0f),
            // Compact centred block of up to 4 lines (max 45% of the width) at the very bottom,
            // where XITE's own captions (now switched off in XITE) sat; style 5's font and box.
            new OverlayStyle("6 Caption", "fonts/Lexend-Variable.ttf", 500, 30, 140, 12, 14, 6, 0f,
                    false, 0.45f, 4, 2),
    };

    static OverlayStyle get(int index) {
        return PRESETS[Math.floorMod(index, PRESETS.length)];
    }

    Typeface typeface(Context ctx) {
        if (fontAsset == null) return Typeface.create(Typeface.DEFAULT, weight, false);
        if (fontAsset.contains("Variable")) {
            Typeface t = new Typeface.Builder(ctx.getAssets(), fontAsset)
                    .setFontVariationSettings("'wght' " + weight).build();
            return t != null ? t : Typeface.DEFAULT_BOLD;
        }
        return Typeface.createFromAsset(ctx.getAssets(), fontAsset);
    }
}
