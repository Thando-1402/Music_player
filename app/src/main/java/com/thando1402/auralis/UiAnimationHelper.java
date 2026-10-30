package com.thando1402.auralis;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

public final class UiAnimationHelper {

    public static final Interpolator OVERSHOOT = new OvershootInterpolator(1.35f);
    public static final Interpolator DECELERATE = new DecelerateInterpolator(1.5f);

    private UiAnimationHelper() {}

    @SuppressLint("ClickableViewAccessibility")
    public static void attachSpringTouch(View... views) {
        if (views == null) return;
        View.OnTouchListener listener = (v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.animate()
                        .scaleX(0.93f)
                        .scaleY(0.93f)
                        .setDuration(90L)
                        .setInterpolator(DECELERATE)
                        .withLayer()
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().cancel();
                v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(190L)
                        .setInterpolator(OVERSHOOT)
                        .withLayer()
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

    public static void pulseBounce(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(80L)
                .setInterpolator(DECELERATE)
                .withLayer()
                .withEndAction(() -> view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(190L)
                        .setInterpolator(OVERSHOOT)
                        .withLayer()
                        .start())
                .start();
    }

    public static void animateAlbumArtPlayState(View artView, boolean isPlaying) {
        if (artView == null) return;
        float targetScale = isPlaying ? 1.0f : 0.96f;
        artView.animate().cancel();
        artView.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(240L)
                .setInterpolator(DECELERATE)
                .withLayer()
                .start();
    }

    public static void animateTrackTransition(View view, boolean forward) {
        if (view == null) return;
        float offset = forward ? 24f : -24f;
        view.animate().cancel();
        view.setAlpha(0.35f);
        view.setTranslationX(offset);
        view.animate()
                .alpha(1.0f)
                .translationX(0f)
                .setDuration(210L)
                .setInterpolator(DECELERATE)
                .withLayer()
                .start();
    }

    public static void crossfadeBitmap(ImageView imageView, Bitmap bitmap) {
        if (imageView == null || bitmap == null) return;
        imageView.animate().cancel();
        imageView.setAlpha(0.45f);
        imageView.setImageBitmap(bitmap);
        imageView.animate()
                .alpha(1.0f)
                .setDuration(200L)
                .setInterpolator(DECELERATE)
                .withLayer()
                .start();
    }

    public static void runStaggeredEntrance(View... views) {
        if (views == null) return;
        int index = 0;
        for (View v : views) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            long delay = 24L * index;
            index++;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(18f);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(210L)
                    .setInterpolator(DECELERATE)
                    .withLayer()
                    .start();
        }
    }

    @SuppressWarnings("deprecation")
    public static void openNowPlaying(Activity host) {
        if (host == null) return;
        host.startActivity(new Intent(host, NowPlayingActivity.class));
        host.overridePendingTransition(R.anim.slide_up_in, 0);
    }

    @SuppressWarnings("deprecation")
    public static void closeNowPlaying(Activity host) {
        if (host == null) return;
        host.finish();
        host.overridePendingTransition(0, R.anim.slide_down_out);
    }
}
