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

    private OverlayStyle(String name, String fontAsset, int weight, int textSp, int boxAlpha, int cornerDp,
            int padHdp, int padVdp, float letterSpacing) {
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
