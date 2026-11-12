package top.niunaijun.blackboxa.runner;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * A frosted glass panel: a dark translucent fill, a soft light that falls on its top left corner,
 * a faint diagonal sheen, and a thin edge that is bright where the light hits it and nearly gone on
 * the far side. The edge is drawn as a ring only, so nothing shines through the middle.
 */
final class GlassPanel extends Drawable {

    /** Everyday panels: cards, popups, the message bar. */
    static GlassPanel card(float radiusPx, float strokePx) {
        return new GlassPanel(radiusPx, strokePx,
                0x47080E22,          // dark glass tint
                0x2AC8DEFF,          // light on the top left corner
                0x14BED7FF, 0x05BED7FF, // diagonal sheen, from top left to bottom right
                new int[]{0x9EFFFFFF, 0x12FFFFFF, 0x0DFFFFFF, 0x4DFFFFFF});
    }

    /** The big manager panel: tinted with cyan and with a brighter, cooler edge. */
    static GlassPanel hero(float radiusPx, float strokePx) {
        return new GlassPanel(radiusPx, strokePx,
                0x66060C1E,
                0x3C7CE4FF,
                0x337CE4FF, 0x12FFFFFF,
                new int[]{0xD9BEF0FF, 0x1A8FA6FF, 0x147CE4FF, 0x80BEF0FF});
    }

    /** Menus, dialogs and the message bar: nearly opaque, so they stay readable over anything. */
    static GlassPanel solid(float radiusPx, float strokePx) {
        return new GlassPanel(radiusPx, strokePx,
                0xF20C1630,
                0x26C8DEFF,
                0x12BED7FF, 0x04BED7FF,
                new int[]{0x8CFFFFFF, 0x1AFFFFFF, 0x12FFFFFF, 0x47FFFFFF});
    }

    private final float radius;
    private final float stroke;
    private final int tint;
    private final int corner;
    private final int washStart;
    private final int washEnd;
    private final int[] rim;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF rimBox = new RectF();
    private int alpha = 255;

    private GlassPanel(float radius, float stroke, int tint, int corner, int washStart,
                       int washEnd, int[] rim) {
        this.radius = radius;
        this.stroke = Math.max(1f, stroke);
        this.tint = tint;
        this.corner = corner;
        this.washStart = washStart;
        this.washEnd = washEnd;
        this.rim = rim;
        fillPaint.setStyle(Paint.Style.FILL);
        cornerPaint.setStyle(Paint.Style.FILL);
        washPaint.setStyle(Paint.Style.FILL);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(this.stroke);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float w = bounds.width();
        float h = bounds.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        box.set(0, 0, w, h);
        float half = stroke / 2f;
        rimBox.set(half, half, w - half, h - half);

        fillPaint.setColor(tint);
        // Light on the top left: a large soft circle centred on the corner.
        float reach = Math.max(w * 1.3f, h * 0.9f);
        cornerPaint.setShader(new RadialGradient(0, 0, reach,
                new int[]{corner, corner & 0x00FFFFFF},
                new float[]{0f, 0.62f}, Shader.TileMode.CLAMP));
        washPaint.setShader(new LinearGradient(0, 0, w, h, washStart, washEnd,
                Shader.TileMode.CLAMP));
        rimPaint.setShader(new LinearGradient(0, 0, w, h, rim,
                new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) {
            return;
        }
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        float r = Math.min(radius, Math.min(box.width(), box.height()) / 2f);
        fillPaint.setAlpha(scaled(android.graphics.Color.alpha(tint)));
        canvas.drawRoundRect(box, r, r, fillPaint);
        cornerPaint.setAlpha(alpha);
        canvas.drawRoundRect(box, r, r, cornerPaint);
        washPaint.setAlpha(alpha);
        canvas.drawRoundRect(box, r, r, washPaint);
        rimPaint.setAlpha(alpha);
        float rr = Math.max(0f, r - stroke / 2f);
        canvas.drawRoundRect(rimBox, rr, rr, rimPaint);
        canvas.restoreToCount(save);
    }

    private int scaled(int channelAlpha) {
        return channelAlpha * alpha / 255;
    }

    @Override
    public void getOutline(Outline outline) {
        if (Build.VERSION.SDK_INT >= 21) {
            outline.setRoundRect(getBounds(), radius);
        }
    }

    @Override
    public void setAlpha(int newAlpha) {
        alpha = newAlpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        cornerPaint.setColorFilter(colorFilter);
        washPaint.setColorFilter(colorFilter);
        rimPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
