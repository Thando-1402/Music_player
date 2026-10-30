package com.thando1402.auralis;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

public final class UiAnimationHelper {

    public static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator(1.6f);
    public static final OvershootInterpolator OVERSHOOT = new OvershootInterpolator(2.2f);
    public static final FastOutSlowInInterpolator FAST_OUT_SLOW_IN = new FastOutSlowInInterpolator();

    private UiAnimationHelper() {}

    /**
     * Attaches lightweight RenderThread spring press physics to buttons/cards.
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void attachSpringTouch(View... views) {
        if (views == null) return;
        View.OnTouchListener listener = (v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.animate()
                        .scaleX(0.90f)
                        .scaleY(0.90f)
                        .setDuration(85)
                        .setInterpolator(DECELERATE)
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().cancel();
                v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(190)
                        .setInterpolator(OVERSHOOT)
                        .start();
            }
            return false;
        };
        for (View view : views) {
            if (view != null) {
                view.setOnTouchListener(listener);
            }
        }
    }

    /**
     * Celebratory pop bounce for toggle buttons (Favorite, Shuffle, Repeat).
     */
    public static void pulseBounce(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.80f);
        view.setScaleY(0.80f);
        view.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(240)
                .setInterpolator(OVERSHOOT)
                .start();
    }

    /**
     * Smoothly expands album art when playing and relaxes it slightly when paused.
     */
    public static void animateAlbumArtPlayState(View artView, boolean isPlaying) {
        if (artView == null) return;
        float targetScale = isPlaying ? 1.0f : 0.95f;
        artView.animate().cancel();
        artView.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(260)
                .setInterpolator(FAST_OUT_SLOW_IN)
                .withLayer()
                .start();
    }

    /**
     * Crisp horizontal slide + crossfade when skipping tracks.
     */
    public static void animateTrackTransition(View view, boolean forward) {
        if (view == null) return;
        float offset = forward ? 28f : -28f;
        view.animate().cancel();
        view.setAlpha(0.25f);
        view.setTranslationX(offset);
        view.animate()
                .alpha(1.0f)
                .translationX(0f)
                .setDuration(210)
                .setInterpolator(DECELERATE)
                .withLayer()
                .start();
    }

    /**
     * Staggered entrance animation for cards/controls without heavy layout passes.
     */
    public static void runStaggeredEntrance(View... views) {
        if (views == null) return;
        long delay = 30L;
        for (View v : views) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.setAlpha(0f);
            v.setTranslationY(24f);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(220)
                    .setInterpolator(DECELERATE)
                    .withLayer()
                    .start();
            delay += 35L;
        }
    }
}
