package top.niunaijun.blackboxa.runner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.DecelerateInterpolator;
import android.widget.CompoundButton;

/**
 * An on/off switch in the glass style: a frosted pill with a light thumb that slides over, and a
 * soft cyan glow when it is on. It is a CompoundButton, so it is used like any other switch.
 */
final class GlassSwitch extends CompoundButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float width;
    private final float height;
    private final float inset;
    private float progress; // 0 = off, 1 = on
    private ValueAnimator animator;
    private Shader onShader;

    GlassSwitch(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        width = 50f * density;
        height = 30f * density;
        inset = 4f * density;
        setBackground(null);
        setClickable(true);
        setFocusable(true);
        setSaveEnabled(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(width), Math.round(height));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        onShader = new LinearGradient(0, 0, w, 0, 0xB07CE4FF, 0xB08FA6FF, Shader.TileMode.CLAMP);
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (!changed) {
            return;
        }
        float target = checked ? 1f : 0f;
        if (isAttachedToWindow() && isLaidOut() && isShown()) {
            animateTo(target);
        } else {
            cancelAnimation();
            progress = target;
            invalidate();
        }
    }

    private void animateTo(float target) {
        cancelAnimation();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(190);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimation();
        progress = isChecked() ? 1f : 0f;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float radius = h / 2f;
        final float p = progress;
        final boolean enabled = isEnabled();
        final float dim = enabled ? 1f : 0.45f;

        track.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);

        // Track: frosted when off, a cyan to blue-violet gradient when on.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1FFFFFFF);
        paint.setAlpha(Math.round(0x1F * (1f - p) * dim));
        canvas.drawRoundRect(track, radius, radius, paint);
        if (p > 0f && onShader != null) {
            paint.setShader(onShader);
            paint.setAlpha(Math.round(255f * p * dim));
            canvas.drawRoundRect(track, radius, radius, paint);
            paint.setShader(null);
        }

        // Edge: a hairline that gets brighter with the glow.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        int edge = blend(0x33FFFFFF, 0x8CBEF0FF, p);
        paint.setColor(edge);
        paint.setAlpha(Math.round(android.graphics.Color.alpha(edge) * dim));
        canvas.drawRoundRect(track, radius, radius, paint);

        // Thumb
        float thumbRadius = radius - inset;
        float left = radius;
        float right = w - radius;
        float cx = left + (right - left) * p;
        paint.setStyle(Paint.Style.FILL);
        // a little shadow under it
        paint.setColor(0x59000000);
        paint.setAlpha(Math.round(0x59 * dim));
        canvas.drawCircle(cx, h / 2f + 1.2f * getResources().getDisplayMetrics().density,
                thumbRadius, paint);
        paint.setColor(blend(0xFFC7D1E8, 0xFFFFFFFF, p));
        paint.setAlpha(Math.round(255f * dim));
        canvas.drawCircle(cx, h / 2f, thumbRadius, paint);
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(android.graphics.Color.alpha(from) * (1f - t) + android.graphics.Color.alpha(to) * t);
        int r = Math.round(android.graphics.Color.red(from) * (1f - t) + android.graphics.Color.red(to) * t);
        int g = Math.round(android.graphics.Color.green(from) * (1f - t) + android.graphics.Color.green(to) * t);
        int b = Math.round(android.graphics.Color.blue(from) * (1f - t) + android.graphics.Color.blue(to) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.Switch.class.getName();
    }

    @Override
    public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(android.widget.Switch.class.getName());
    }
}
