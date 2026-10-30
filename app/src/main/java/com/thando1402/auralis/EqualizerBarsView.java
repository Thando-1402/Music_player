package com.thando1402.auralis;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import java.util.Random;

public class EqualizerBarsView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] barHeights = new int[3];
    private final int[] targetHeights = new int[3];
    private final Random random = new Random();
    private ValueAnimator animator;
    private boolean isAnimating = false;
    private int barColor;

    // Values that adapt nicely to small phone screens (e.g. Nokia 1.4)
    private float barWidth;
    private float spacing;
    private float maxBarHeight;

    public EqualizerBarsView(Context context) {
        super(context);
        init();
    }

    public EqualizerBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barColor = ContextCompat.getColor(getContext(), R.color.accent_blue);
        paint.setColor(barColor);
        paint.setStyle(Paint.Style.FILL);

        // Convert dp to px
        float density = getResources().getDisplayMetrics().density;
        barWidth = 4f * density;
        spacing = 3f * density;
        maxBarHeight = 14f * density;
    }

    public void animateBars(boolean play) {
        if (play && !isAnimating) {
            isAnimating = true;
            setVisibility(VISIBLE);
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0, 1);
                animator.setDuration(180);
                animator.setInterpolator(new LinearInterpolator());
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.addUpdateListener(animation -> {
                    // Smoothly interpolate each bar towards its randomized target height
                    float fraction = (float) animation.getAnimatedValue();
                    for (int i = 0; i < 3; i++) {
                        barHeights[i] = (int) (barHeights[i] + (targetHeights[i] - barHeights[i]) * 0.2f);
                    }
                    invalidate();
                });

                // Assign new random targets slightly less often than the update loop
                animator.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationRepeat(android.animation.Animator animation) {
                        for (int i = 0; i < 3; i++) {
                            targetHeights[i] = (int) (random.nextFloat() * maxBarHeight);
                            // Ensure they don't drop to 0 so the view doesn't look empty
                            if (targetHeights[i] < maxBarHeight * 0.2f) {
                                targetHeights[i] = (int) (maxBarHeight * 0.2f);
                            }
                        }
                    }
                });
            }
            animator.start();
        } else if (!play) {
            isAnimating = false;
            if (animator != null) {
                animator.cancel();
            }
            // Freeze bars at a low height when paused
            for (int i = 0; i < 3; i++) {
                barHeights[i] = (int) (maxBarHeight * 0.15f);
            }
            invalidate();
            // Optional: setVisibility(GONE) if you want them to vanish completely when paused
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float x = (getWidth() - (3 * barWidth + 2 * spacing)) / 2f;
        float bottomY = getHeight() - (getHeight() - maxBarHeight) / 2f;

        for (int i = 0; i < 3; i++) {
            float height = Math.min(Math.max(barHeights[i], maxBarHeight * 0.15f), maxBarHeight);
            float topY = bottomY - height;

            rect.set(x, topY, x + barWidth, bottomY);
            // Draw slightly rounded caps for a modern look
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);

            x += barWidth + spacing;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
