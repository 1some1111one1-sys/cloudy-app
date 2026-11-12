package top.niunaijun.blackboxa.runner;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;

/**
 * Colours and small helpers for the "calm liquid glass" look: a deep navy-teal backdrop with soft
 * lights behind it, frosted translucent panels with a lit edge, cool cyan accents, and a little
 * monospace lettering for the quiet sci-fi touches.
 */
final class Glass {
    private Glass() {
    }

    // Backdrop, top to bottom
    static final int BG_TOP = 0xFF060A18;
    static final int BG_MID = 0xFF0B1530;
    static final int BG_BOTTOM = 0xFF08283A;

    // Soft lights behind the glass
    static final int ORB_CYAN = 0xFF3AB8FF;
    static final int ORB_VIOLET = 0xFF7A6BFF;
    static final int ORB_TEAL = 0xFF22D6C0;

    // Text
    static final int TEXT = 0xFFF4F7FF;
    static final int TEXT2 = 0xFFC6D0E6;
    static final int TEXT3 = 0xFFB0BCD4;
    /** Dark ink for text on the bright cyan buttons. */
    static final int INK = 0xFF061022;

    // Accents
    static final int CYAN = 0xFF7CE4FF;
    static final int BLUE = 0xFF8FA6FF;
    static final int MINT = 0xFF6CF2C8;
    static final int AMBER = 0xFFFFCB7A;
    static final int ROSE = 0xFFFF8A9B;

    static final int HAIRLINE = 0x14FFFFFF;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(255f * alpha) << 24);
    }

    /** Titles and row labels: a little heavier than body text, never loud. */
    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** The quiet sci-fi lettering: small labels, package names, status words. */
    static Typeface mono() {
        return Typeface.MONOSPACE;
    }

    /** A rounded shape with an optional hairline border. */
    static GradientDrawable shape(int fill, int stroke, float radiusPx, float strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            d.setStroke(Math.max(1, Math.round(strokePx)), stroke);
        }
        return d;
    }

    /** The bright ice-blue gradient of the main buttons. */
    static GradientDrawable lightButton(float radiusPx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF8DEBFF, 0xFF9DB2FF});
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** A button face with a soft light-blue press effect on top. */
    static Drawable pressable(Drawable face, float radiusPx, int rippleColor) {
        if (Build.VERSION.SDK_INT >= 21) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(radiusPx);
            return new RippleDrawable(ColorStateList.valueOf(rippleColor), face, mask);
        }
        return face;
    }

    /** Touch feedback for a row that fills its panel edge to edge. */
    static Drawable rowRipple() {
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x2E7CE4FF), null,
                    new ColorDrawable(Color.WHITE));
        }
        return new ColorDrawable(Color.TRANSPARENT);
    }

    /** Round touch feedback for an icon-only button. */
    static Drawable circleRipple() {
        if (Build.VERSION.SDK_INT >= 21) {
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            return new RippleDrawable(ColorStateList.valueOf(0x337CE4FF), null, mask);
        }
        return new ColorDrawable(Color.TRANSPARENT);
    }
}
