package com.thando1402.auralis;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class NowPlayingActivity extends AppCompatActivity {

    private FloatingActionButton btnPlayPause;
    private ShapeableImageView heroAlbumArt;
    private AppCompatImageButton btnFavorite, btnShuffle, btnRepeat, btnCollapse, btnPrev, btnNext;
    private TextView textTitle, textArtist, textElapsed, textRemaining, badgeDsp;
    private AppCompatSeekBar seekBar;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private boolean isUserSeeking = false;
    private boolean isFavorite = false;

    private final int COLOR_ACCENT_BLUE = Color.parseColor("#00B0FF");
    private final int COLOR_INACTIVE = Color.parseColor("#757575");
    private final int COLOR_PRIMARY_WHITE = Color.parseColor("#FFFFFF");

    private final Runnable updateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaController != null && mediaController.isPlaying() && !isUserSeeking) {
                updateProgressUI();
            }
            progressHandler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            updateMetadataUI();
            updateProgressUI();
        }

        @Override
        public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
            updateMetadataUI();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            animatePlayPauseState(isPlaying);
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            btnShuffle.setImageTintList(ColorStateList.valueOf(shuffleModeEnabled ? COLOR_ACCENT_BLUE : COLOR_INACTIVE));
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            btnRepeat.setImageTintList(ColorStateList.valueOf(repeatMode != Player.REPEAT_MODE_OFF ? COLOR_ACCENT_BLUE : COLOR_INACTIVE));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        btnPlayPause = findViewById(R.id.btn_play_pause_hero);
        heroAlbumArt = findViewById(R.id.hero_album_art);
        btnFavorite = findViewById(R.id.btn_favorite);
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnCollapse = findViewById(R.id.btn_collapse);
        btnPrev = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);
        textTitle = findViewById(R.id.np_track_title);
        textArtist = findViewById(R.id.np_artist_name);
        textElapsed = findViewById(R.id.text_elapsed_time);
        textRemaining = findViewById(R.id.text_remaining_time);
        seekBar = findViewById(R.id.track_seek_bar);
        badgeDsp = findViewById(R.id.badge_dsp_profile);

        // 1. Play/Pause Button with 60fps Micro-Bounce
        btnPlayPause.setOnClickListener(v -> {
            if (mediaController != null) {
                if (mediaController.isPlaying()) {
                    mediaController.pause();
                } else {
                    mediaController.play();
                }
            }
        });

        // 2. Previous & Next Controls
        btnPrev.setOnClickListener(v -> {
            if (mediaController != null) {
                mediaController.seekToPrevious();
            }
        });
        btnNext.setOnClickListener(v -> {
            if (mediaController != null) {
                mediaController.seekToNext();
            }
        });

        // 3. Favorite Heart Spring Pop Animation
        btnFavorite.setOnClickListener(v -> animateFavoriteButton());

        // 4. Shuffle Toggle with 180-deg Rotation
        btnShuffle.setOnClickListener(v -> {
            if (mediaController != null) {
                boolean newState = !mediaController.getShuffleModeEnabled();
                mediaController.setShuffleModeEnabled(newState);
                btnShuffle.animate().rotationBy(180f).setDuration(220).start();
                btnShuffle.setImageTintList(ColorStateList.valueOf(newState ? COLOR_ACCENT_BLUE : COLOR_INACTIVE));
            }
        });

        // 5. Repeat Mode Cycle (Off -> All -> One)
        btnRepeat.setOnClickListener(v -> {
            if (mediaController != null) {
                int current = mediaController.getRepeatMode();
                int next = (current == Player.REPEAT_MODE_OFF) ? Player.REPEAT_MODE_ALL :
                           (current == Player.REPEAT_MODE_ALL) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF;
                mediaController.setRepeatMode(next);
                btnRepeat.setImageTintList(ColorStateList.valueOf(next != Player.REPEAT_MODE_OFF ? COLOR_ACCENT_BLUE : COLOR_INACTIVE));
            }
        });

        // 6. SeekBar Scrubbing
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaController != null) {
                    long duration = mediaController.getDuration();
                    if (duration > 0) {
                        long newPosition = (duration * progress) / 1000L;
                        textElapsed.setText(formatTime(newPosition));
                        textRemaining.setText("-" + formatTime(Math.max(0, duration - newPosition)));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mediaController != null) {
                    long duration = mediaController.getDuration();
                    if (duration > 0) {
                        long newPosition = (duration * seekBar.getProgress()) / 1000L;
                        mediaController.seekTo(newPosition);
                    }
                }
                isUserSeeking = false;
            }
        });

        // 7. DSP Badge opens Equalizer
        badgeDsp.setOnClickListener(v -> startActivity(new Intent(this, EqualizerActivity.class)));

        // Update audio route on pill click
        View dacPill = findViewById(R.id.dac_hardware_pill);
        if (dacPill != null) {
            dacPill.setOnClickListener(v -> updateHardwareDacUI());
        }

        // 8. Collapse / Back Transitions
        btnCollapse.setOnClickListener(v -> closeWithAnimation());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closeWithAnimation();
            }
        });
    }

    private void closeWithAnimation() {
        finish();
        overridePendingTransition(0, R.anim.slide_down_out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateHardwareDacUI();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                mediaController.addListener(playerListener);
                updateMetadataUI();
                updateProgressUI();
                animatePlayPauseState(mediaController.isPlaying());
                btnShuffle.setImageTintList(ColorStateList.valueOf(
                        mediaController.getShuffleModeEnabled() ? COLOR_ACCENT_BLUE : COLOR_INACTIVE));
                btnRepeat.setImageTintList(ColorStateList.valueOf(
                        mediaController.getRepeatMode() != Player.REPEAT_MODE_OFF ? COLOR_ACCENT_BLUE : COLOR_INACTIVE));
                progressHandler.post(updateProgressRunnable);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        progressHandler.removeCallbacks(updateProgressRunnable);
        if (mediaController != null) {
            mediaController.removeListener(playerListener);
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
    }

    private void updateMetadataUI() {
        if (mediaController == null) return;
        MediaMetadata meta = mediaController.getMediaMetadata();
        MediaItem currentItem = mediaController.getCurrentMediaItem();

        CharSequence title = meta.title;
        CharSequence artist = meta.artist;

        if ((title == null || title.length() == 0) && currentItem != null && currentItem.mediaMetadata != null) {
            title = currentItem.mediaMetadata.title;
        }
        if ((artist == null || artist.length() == 0) && currentItem != null && currentItem.mediaMetadata != null) {
            artist = currentItem.mediaMetadata.artist;
        }

        android.net.Uri artworkUri = meta.artworkUri;
        if (artworkUri == null && currentItem != null && currentItem.mediaMetadata != null) {
            artworkUri = currentItem.mediaMetadata.artworkUri;
        }
        if (heroAlbumArt != null) {
            if (artworkUri != null) {
                heroAlbumArt.setImageURI(artworkUri);
            } else {
                heroAlbumArt.setImageResource(R.drawable.ic_album_placeholder_large);
            }
        }

        textTitle.setText(title != null && title.length() > 0 ? title : "Unknown Track");
        textArtist.setText(artist != null && artist.length() > 0 ? artist : "Unknown Artist");
    }

    private void updateProgressUI() {
        if (mediaController == null) return;
        long duration = mediaController.getDuration();
        long position = mediaController.getCurrentPosition();
        if (duration > 0) {
            int progress = (int) ((position * 1000L) / duration);
            seekBar.setProgress(progress);
            textElapsed.setText(formatTime(position));
            textRemaining.setText("-" + formatTime(Math.max(0, duration - position)));
        }
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void updateHardwareDacUI() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        TextView textDevice = findViewById(R.id.text_output_device);
        TextView textCodec = findViewById(R.id.text_output_codec);
        android.widget.ImageView iconOutput = findViewById(R.id.icon_cast_output);

        if (textDevice == null || textCodec == null || iconOutput == null) return;

        String deviceName = "Internal Speaker";
        String codecDetails = "Direct PCM • Built-in DAC active";
        int iconRes = R.drawable.ic_headphones_24; // We'll use headphones icon as default fallback

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
                    device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET || 
                    device.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    
                    deviceName = device.getProductName() != null ? device.getProductName().toString() : "Bluetooth Device";
                    codecDetails = "A2DP Wireless Audio Route";
                    iconRes = R.drawable.ic_headphones_24; 
                    break;
                } else if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
                           device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    
                    deviceName = "Wired Headphones";
                    codecDetails = "Analog Output • Internal DAC";
                    iconRes = R.drawable.ic_headphones_24;
                    break;
                } else if (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || 
                           device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    
                    deviceName = device.getProductName() != null ? device.getProductName().toString() : "USB DAC / Audio Device";
                    codecDetails = "Bit-Perfect Digital Passthrough";
                    iconRes = R.drawable.ic_headphones_24;
                    break;
                }
            }
        }

        textDevice.setText(deviceName);
        textCodec.setText(codecDetails);
        iconOutput.setImageResource(iconRes);
    }

    private void animatePlayPauseState(boolean playing) {
        btnPlayPause.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(80)
                .withEndAction(() -> {
                    btnPlayPause.setImageResource(playing ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_36);
                    btnPlayPause.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(120)
                            .setInterpolator(new OvershootInterpolator(2.0f))
                            .start();
                }).start();

        float targetScale = playing ? 1.0f : 0.94f;
        heroAlbumArt.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(240)
                .start();
    }

    private void animateFavoriteButton() {
        isFavorite = !isFavorite;

        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(btnFavorite, View.SCALE_X, 0.7f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(btnFavorite, View.SCALE_Y, 0.7f);
        scaleDownX.setDuration(100);
        scaleDownY.setDuration(100);

        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(btnFavorite, View.SCALE_X, 1.25f, 1.0f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(btnFavorite, View.SCALE_Y, 1.25f, 1.0f);
        scaleUpX.setDuration(250);
        scaleUpY.setDuration(250);
        scaleUpX.setInterpolator(new OvershootInterpolator(3.0f));
        scaleUpY.setInterpolator(new OvershootInterpolator(3.0f));

        scaleDownX.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                btnFavorite.setImageResource(isFavorite ? R.drawable.ic_favorite_24 : R.drawable.ic_favorite_border_24);
                btnFavorite.setImageTintList(ColorStateList.valueOf(isFavorite ? COLOR_ACCENT_BLUE : COLOR_PRIMARY_WHITE));
            }
        });

        AnimatorSet set = new AnimatorSet();
        set.play(scaleDownX).with(scaleDownY);
        set.play(scaleUpX).after(scaleDownX);
        set.play(scaleUpY).after(scaleDownY);
        set.start();
    }
}