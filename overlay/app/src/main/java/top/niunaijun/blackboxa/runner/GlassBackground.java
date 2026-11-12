package top.niunaijun.blackboxa.runner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The live backdrop of the app: a deep blue gradient with soft lights that drift slowly and change
 * shade (mostly blue, with violet, teal and a hint of rose), a few flowing contour lines, rings
 * that spread out from the brightest light, and a faint grid of dots that creeps along.
 *
 * It is drawn with a handful of cached gradients that are only moved around, so a frame is cheap,
 * and it runs at about 30 frames per second, without a pause, for as long as the screen shows it
 * (it ignores the phone's animation and battery saver settings on purpose).
 */
public class GlassBackground extends View {
    private static final long FRAME_MS = 33;
    /** Where on its path every light sits when nothing moves (animations off). */
    private static final double STILL_SECONDS = 9.0;
    private static final double TWO_PI = Math.PI * 2.0;

    /** One soft light: it drifts along a slow loop and fades between two shades. */
    private static final class Blob {
        final float cx, cy;          // centre of its loop, as a fraction of the view
        final float ax, ay;          // size of the loop, as a fraction of the view
        final float px, py;          // seconds for one trip along each axis
        final float ox, oy;          // where on the loop it starts
        final float radius;          // as a fraction of the base size
        final int colorA, colorB;
        final float peak;            // brightness at its centre, 0..1
        final float fade;            // seconds for one change of shade
        final float fadeOffset;
        RadialGradient shaderA, shaderB;

        Blob(float cx, float cy, float ax, float ay, float px, float py, float ox, float oy,
             float radius, int colorA, int colorB, float peak, float fade, float fadeOffset) {
            this.cx = cx;
            this.cy = cy;
            this.ax = ax;
            this.ay = ay;
            this.px = px;
            this.py = py;
            this.ox = ox;
            this.oy = oy;
            this.radius = radius;
            this.colorA = colorA;
            this.colorB = colorB;
            this.peak = peak;
            this.fade = fade;
            this.fadeOffset = fadeOffset;
        }
    }

    private final Blob[] blobs = {
            // The big azure light at the top right: the brightest thing on screen.
            new Blob(0.78f, 0.18f, 0.20f, 0.09f, 26f, 34f, 0.0f, 1.3f, 0.62f,
                    0xFF2F80FF, 0xFF3AB8FF, 0.40f, 30f, 0.0f),
            // A wide deep-blue wash that gives the whole screen its colour.
            new Blob(0.40f, 0.40f, 0.26f, 0.12f, 38f, 29f, 2.1f, 0.4f, 0.85f,
                    0xFF1B4FD6, 0xFF2C6BFF, 0.30f, 44f, 1.7f),
            // Violet on the left, drifting towards magenta.
            new Blob(0.12f, 0.55f, 0.18f, 0.11f, 33f, 24f, 4.0f, 2.4f, 0.62f,
                    0xFF6A5CFF, 0xFFA06BFF, 0.34f, 36f, 3.1f),
            // Teal at the lower right, drifting towards sky blue.
            new Blob(0.86f, 0.80f, 0.16f, 0.10f, 24f, 37f, 1.2f, 3.3f, 0.66f,
                    0xFF1FD0C0, 0xFF3AB8FF, 0.28f, 34f, 4.4f),
            // Ice blue through the middle.
            new Blob(0.55f, 0.68f, 0.24f, 0.10f, 47f, 31f, 5.2f, 0.9f, 0.50f,
                    0xFF3AB8FF, 0xFF5B8CFF, 0.22f, 46f, 5.6f),
            // Just a hint of rose at the bottom left.
            new Blob(0.24f, 0.90f, 0.20f, 0.06f, 42f, 27f, 3.4f, 5.0f, 0.45f,
                    0xFFFF6BB5, 0xFF7A6BFF, 0.16f, 40f, 0.8f),
    };

    /** Tiny lights that float slowly upwards and twinkle. */
    private static final int MOTES = 18;
    private final float[] moteX = new float[MOTES];     // start, as a fraction of the width
    private final float[] moteY = new float[MOTES];     // start, as a fraction of the height
    private final float[] moteRise = new float[MOTES];  // dp per second, upwards
    private final float[] moteSway = new float[MOTES];  // dp of side-to-side sway
    private final float[] moteSize = new float[MOTES];  // dp
    private final float[] motePhase = new float[MOTES];
    private final float[] moteTwinkle = new float[MOTES]; // seconds per twinkle
    private final Paint motePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private LinearGradient lineCyan;
    private LinearGradient lineBlue;

    private final Paint basePaint = new Paint();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint();
    private final Paint edgePaint = new Paint();
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();

    private Bitmap dotTile;
    private BitmapShader dotShader;
    private int tilePx;
    private final boolean fadeEdges;

    private float density;
    private float baseSize;
    private boolean running;
    private boolean attached;
    private double clock = STILL_SECONDS;
    private long lastFrame;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    public GlassBackground(Context context) {
        this(context, false);
    }

    /**
     * @param fadeEdges blend the top and bottom edges into the flat colours the status bar and the
     *                  navigation bar use, for screens that do not draw behind the bars
     */
    public GlassBackground(Context context, boolean fadeEdges) {
        super(context);
        this.fadeEdges = fadeEdges;
        init();
    }

    public GlassBackground(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.fadeEdges = true;
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1f, density));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, density));
        java.util.Random random = new java.util.Random(11);
        for (int i = 0; i < MOTES; i++) {
            moteX[i] = random.nextFloat();
            moteY[i] = random.nextFloat();
            moteRise[i] = 4f + random.nextFloat() * 9f;
            moteSway[i] = 6f + random.nextFloat() * 16f;
            moteSize[i] = 0.9f + random.nextFloat() * 1.3f;
            motePhase[i] = random.nextFloat() * (float) TWO_PI;
            moteTwinkle[i] = 4f + random.nextFloat() * 6f;
        }
    }

    // ------------------------------------------------------------------ size and shaders

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        basePaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Glass.BG_TOP, Glass.BG_MID, Glass.BG_BOTTOM},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));

        baseSize = Math.max(w, h * 0.62f);
        for (Blob blob : blobs) {
            float r = baseSize * blob.radius;
            blob.shaderA = glow(blob.colorA, blob.peak, r);
            blob.shaderB = glow(blob.colorB, blob.peak, r);
        }

        // The contour lines are strongest in the middle and fade out towards both sides.
        lineCyan = lineShader(Glass.CYAN, w);
        lineBlue = lineShader(Glass.BLUE, w);

        buildDots();

        if (fadeEdges) {
            float top = 72f * density;
            float bottom = 56f * density;
            // One gradient for both edges: opaque, clear in between, opaque again.
            float a = Math.min(0.45f, top / h);
            float b = Math.max(0.55f, 1f - bottom / h);
            edgePaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Glass.BG_TOP, 0x00060A18, 0x0008283A, Glass.BG_BOTTOM},
                    new float[]{0f, a, b, 1f}, Shader.TileMode.CLAMP));
        }
    }

    private static LinearGradient lineShader(int color, int width) {
        int rgb = color & 0x00FFFFFF;
        return new LinearGradient(0, 0, width, 0,
                new int[]{rgb, rgb | 0xFF000000, rgb | 0xFF000000, rgb},
                new float[]{0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A soft round light: bright in the middle, fading smoothly to nothing. */
    private static RadialGradient glow(int color, float peak, float radius) {
        int rgb = color & 0x00FFFFFF;
        return new RadialGradient(0, 0, radius,
                new int[]{
                        rgb | (Math.round(255f * peak) << 24),
                        rgb | (Math.round(255f * peak * 0.55f) << 24),
                        rgb | (Math.round(255f * peak * 0.16f) << 24),
                        rgb},
                new float[]{0f, 0.35f, 0.70f, 1f}, Shader.TileMode.CLAMP);
    }

    private void buildDots() {
        tilePx = Math.max(8, Math.round(22f * density));
        if (dotTile != null) {
            dotTile.recycle();
        }
        dotTile = Bitmap.createBitmap(tilePx, tilePx, Bitmap.Config.ARGB_8888);
        Canvas tile = new Canvas(dotTile);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setAlpha(255);
        tile.drawCircle(tilePx / 2f, tilePx / 2f, Math.max(0.8f, density * 0.9f), p);
        dotShader = new BitmapShader(dotTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        dotPaint.setShader(dotShader);
        dotPaint.setAlpha(26);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0 || blobs[0].shaderA == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (running) {
            if (lastFrame != 0) {
                // (a long gap, for example after the screen was off, must not make things jump)
                clock += Math.min(now - lastFrame, 100L) / 1000.0;
            }
            lastFrame = now;
        }
        final double t = clock;

        canvas.drawRect(0, 0, w, h, basePaint);

        // The soft lights, each fading between two shades.
        float brightX = 0f;
        float brightY = 0f;
        for (int i = 0; i < blobs.length; i++) {
            Blob b = blobs[i];
            float x = (float) (b.cx + b.ax * Math.sin(TWO_PI * t / b.px + b.ox)) * w;
            float y = (float) (b.cy + b.ay * Math.sin(TWO_PI * t / b.py + b.oy)) * h;
            float mix = (float) (0.5 + 0.5 * Math.sin(TWO_PI * t / b.fade + b.fadeOffset));
            float r = baseSize * b.radius;
            if (i == 0) {
                brightX = x;
                brightY = y;
            }
            // Each light also swells and shrinks a little, so the glow always seems to breathe.
            float breath = (float) (1.0 + 0.09 * Math.sin(TWO_PI * t / (b.py * 0.45) + b.ox));
            canvas.save();
            canvas.translate(x, y);
            canvas.scale(breath, breath);
            blobPaint.setShader(b.shaderA);
            blobPaint.setAlpha(Math.round(255f * (1f - mix)));
            canvas.drawCircle(0, 0, r, blobPaint);
            blobPaint.setShader(b.shaderB);
            blobPaint.setAlpha(Math.round(255f * mix));
            canvas.drawCircle(0, 0, r, blobPaint);
            canvas.restore();
        }

        drawRings(canvas, t, brightX, brightY);
        drawDots(canvas, t, w, h);
        drawContours(canvas, t, w, h);
        drawMotes(canvas, t, w, h);

        if (fadeEdges && edgePaint.getShader() != null) {
            canvas.drawRect(0, 0, w, h, edgePaint);
        }

        if (running) {
            postOnAnimationDelayed(tick, FRAME_MS);
        }
    }

    /** Thin rings that spread out from the brightest light and fade as they grow. */
    private void drawRings(Canvas canvas, double t, float cx, float cy) {
        final float period = 11f;
        final float maxRadius = baseSize * 0.62f;
        for (int i = 0; i < 3; i++) {
            float phase = (float) (((t / period) + i / 3.0) % 1.0);
            float radius = maxRadius * (0.18f + 0.82f * phase);
            float fade = (1f - phase);
            int alpha = Math.round(255f * 0.10f * fade * fade);
            if (alpha < 2) {
                continue;
            }
            ringPaint.setColor(Glass.withAlpha(Glass.CYAN, alpha / 255f));
            canvas.drawCircle(cx, cy, radius, ringPaint);
        }
    }

    /** The dot grid, creeping diagonally so slowly that you only notice it over time. */
    private void drawDots(Canvas canvas, double t, int w, int h) {
        if (dotShader == null) {
            return;
        }
        float dx = (float) ((t * 4.5 * density) % tilePx);
        float dy = (float) ((t * 2.8 * density) % tilePx);
        matrix.setTranslate(dx, dy);
        dotShader.setLocalMatrix(matrix);
        canvas.drawRect(0, 0, w, h, dotPaint);
    }

    private static float wrap(float value, float max) {
        float r = value % max;
        return r < 0 ? r + max : r;
    }

    private void drawMotes(Canvas canvas, double t, int w, int h) {
        final float margin = 12f * density;
        for (int i = 0; i < MOTES; i++) {
            float y = wrap(moteY[i] * h - (float) t * moteRise[i] * density, h + 2 * margin) - margin;
            float x = moteX[i] * w
                    + (float) Math.sin(t * 0.35 + motePhase[i]) * moteSway[i] * density;
            float twinkle = (float) (0.5 + 0.5 * Math.sin(TWO_PI * t / moteTwinkle[i] + motePhase[i]));
            int alpha = Math.round(255f * (0.10f + 0.30f * twinkle));
            motePaint.setColor((i % 3 == 0) ? 0xFFFFFFFF : Glass.CYAN);
            motePaint.setAlpha(alpha);
            canvas.drawCircle(x, y, moteSize[i] * density, motePaint);
        }
    }

    /** A few long, gentle wave lines, like contours on a map that slowly flows. */
    private void drawContours(Canvas canvas, double t, int w, int h) {
        final int lines = 5;
        final float step = 14f * density;
        for (int i = 0; i < lines; i++) {
            float baseY = h * (0.14f + 0.17f * i);
            float amp = (16f + 5f * i) * density;
            double k1 = TWO_PI / ((300 + 40 * i) * density);
            double k2 = TWO_PI / ((170 + 25 * i) * density);
            double s1 = 0.085 + 0.008 * i;
            double s2 = 0.110 - 0.006 * i;
            path.rewind();
            boolean first = true;
            for (float x = -step; x <= w + step; x += step) {
                double y = baseY
                        + amp * Math.sin(x * k1 + t * s1 * TWO_PI + i * 1.3)
                        + amp * 0.45 * Math.sin(x * k2 - t * s2 * TWO_PI + i * 0.7);
                if (first) {
                    path.moveTo(x, (float) y);
                    first = false;
                } else {
                    path.lineTo(x, (float) y);
                }
            }
            // Alternate between a cool cyan and a soft blue-violet.
            linePaint.setShader(i % 2 == 0 ? lineCyan : lineBlue);
            linePaint.setAlpha(Math.round(255f * 0.22f));
            canvas.drawPath(path, linePaint);
        }
    }

    // ------------------------------------------------------------------ running and stopping

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunning();
    }

    private void updateRunning() {
        // It moves whenever it can be seen, whatever the phone's animation or battery settings say.
        boolean shouldRun = attached && getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE;
        if (shouldRun && !running) {
            running = true;
            lastFrame = 0;
            invalidate();
        } else if (!shouldRun && running) {
            stop();
            invalidate();
        }
    }

    private void stop() {
        running = false;
        removeCallbacks(tick);
    }
}
