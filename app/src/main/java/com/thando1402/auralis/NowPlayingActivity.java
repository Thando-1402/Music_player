package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import androidx.palette.graphics.Palette;
import java.io.InputStream;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;

public class NowPlayingActivity extends AppCompatActivity {

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

    private TextView tvTitle, tvArtist, tvTimeCurrent, tvTimeRemaining;
    private TextView tvDeviceName, tvDeviceCodec, tvScrolledLyrics;
    private View lyricsScrollContainer;
    private ImageView ivArt;
    private FloatingActionButton btnPlayPause;
    private MaterialButton btnFav, btnShuffle, btnRepeat;
    private SeekBar seekBar;
    private boolean isTracking = false;
    private AudioDeviceCallback audioDeviceCallback;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            syncProgress();
            progressHandler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            syncMetadata();
        }
        @Override
        public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
            syncMetadata();
        }
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            syncPlayPauseState();
            if (isPlaying) progressHandler.post(progressRunnable);
            else progressHandler.removeCallbacks(progressRunnable);
        }
        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            syncShuffleState();
        }
        @Override
        public void onRepeatModeChanged(int repeatMode) {
            syncRepeatState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        tvTitle = findViewById(R.id.tv_track_title);
        tvTitle.setSelected(true);
        tvArtist = findViewById(R.id.tv_track_artist_album);
        tvTimeCurrent = findViewById(R.id.tv_time_current);
        tvTimeRemaining = findViewById(R.id.tv_time_remaining);
        tvDeviceName = findViewById(R.id.tv_connected_device_name);
        tvDeviceCodec = findViewById(R.id.tv_connected_device_codec);
        tvScrolledLyrics = findViewById(R.id.tv_scrolled_lyrics);
        lyricsScrollContainer = findViewById(R.id.lyrics_scroll_container);
        ivArt = findViewById(R.id.iv_hero_album_art);
        seekBar = findViewById(R.id.slider_playback_progress);

        findViewById(R.id.btn_collapse).setOnClickListener(v -> {
            finish();
            overridePendingTransition(0, R.anim.slide_down_out);
        });
        findViewById(R.id.btn_queue).setOnClickListener(v ->
                startActivity(new Intent(NowPlayingActivity.this, PlaylistsActivity.class)));

        btnPlayPause = findViewById(R.id.btn_play_pause_fab);
        btnPlayPause.setOnClickListener(v -> {
            if (mediaController != null) {
                if (mediaController.isPlaying()) mediaController.pause();
                else mediaController.play();
                btnPlayPause.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90)
                        .withEndAction(() -> btnPlayPause.animate().scaleX(1f).scaleY(1f)
                                .setInterpolator(new OvershootInterpolator()).start()).start();
            }
        });

        findViewById(R.id.btn_skip_previous).setOnClickListener(v -> {
            if (mediaController != null) mediaController.seekToPrevious();
        });
        findViewById(R.id.btn_skip_next).setOnClickListener(v -> {
            if (mediaController != null) mediaController.seekToNext();
        });

        btnShuffle = findViewById(R.id.btn_toggle_shuffle);
        btnShuffle.setOnClickListener(v -> {
            if (mediaController != null) {
                mediaController.setShuffleModeEnabled(!mediaController.getShuffleModeEnabled());
            }
        });

        btnRepeat = findViewById(R.id.btn_toggle_repeat);
        btnRepeat.setOnClickListener(v -> {
            if (mediaController != null) {
                int mode = mediaController.getRepeatMode();
                mediaController.setRepeatMode(mode == Player.REPEAT_MODE_OFF
                        ? Player.REPEAT_MODE_ALL
                        : (mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF));
            }
        });

        findViewById(R.id.btn_lyrics_sheet).setOnClickListener(v -> {
            if (lyricsScrollContainer != null) {
                boolean isVisible = lyricsScrollContainer.getVisibility() == View.VISIBLE;
                lyricsScrollContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
                ivArt.setVisibility(isVisible ? View.VISIBLE : View.GONE);
                // Also toggle the scrims if you want, but they're mostly behind lyrics anyway
            }
        });

        btnFav = findViewById(R.id.btn_toggle_favorite);
        if (btnFav != null) {
            btnFav.setOnClickListener(v -> {
                if (mediaController != null) {
                    MediaItem item = mediaController.getCurrentMediaItem();
                    if (item != null && !item.mediaId.isEmpty()) {
                        boolean newFav = !SongMetadataManager.isFavorite(this, item.mediaId);
                        SongMetadataManager.setFavorite(this, item.mediaId, newFav);
                        syncFavoriteState();
                        Toast.makeText(this, newFav ? "Added to Favourites ♥" : "Removed from Favourites", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        findViewById(R.id.card_audio_route_output).setOnClickListener(v -> {
            detectActiveDacOutput();
            Toast.makeText(this, "Hardware audio route refreshed", Toast.LENGTH_SHORT).show();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaController != null) {
                    long duration = mediaController.getDuration();
                    if (duration > 0) {
                        long newPos = (duration * progress) / 1000L;
                        tvTimeCurrent.setText(formatTime(newPos));
                        tvTimeRemaining.setText("-" + formatTime(duration - newPos));
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isTracking = true;
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mediaController != null) {
                    long duration = mediaController.getDuration();
                    if (duration > 0) {
                        mediaController.seekTo((duration * seekBar.getProgress()) / 1000L);
                    }
                }
                isTracking = false;
            }
        });

        detectActiveDacOutput();
    }

    private void detectActiveDacOutput() {
        if (tvDeviceName == null || tvDeviceCodec == null) return;
        String deviceTitle = "Internal Hi-Fi Speaker DAC";
        String codecLabel = "24-BIT PCM";
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int type = d.getType();
                    String prod = d.getProductName() != null ? d.getProductName().toString() : "";
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                        deviceTitle = "3.5mm Wired Headphones";
                        codecLabel = "ANALOG 24/96";
                        break;
                    } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        deviceTitle = !prod.isEmpty() ? prod : "Bluetooth Audio";
                        codecLabel = "A2DP / LDAC";
                        break;
                    } else if (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                        deviceTitle = !prod.isEmpty() ? prod : "External USB DAC";
                        codecLabel = "USB BIT-PERFECT";
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        tvDeviceName.setText(deviceTitle);
        tvDeviceCodec.setText(codecLabel);
    }

    @Override
    protected void onStart() {
        super.onStart();
        
        audioDeviceCallback = PlaybackStateHelper.registerLiveOutputTracker(this, tvDeviceName, tvDeviceCodec);
        
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                mediaController.addListener(playerListener);
                syncMetadata();
                syncPlayPauseState();
                syncShuffleState();
                syncRepeatState();
                if (mediaController.isPlaying()) progressHandler.post(progressRunnable);
            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        PlaybackStateHelper.unregisterLiveOutputTracker(this, audioDeviceCallback);
        if (mediaController != null) {
            PlaybackStateHelper.saveLastPlayedState(this, mediaController);
        }
        progressHandler.removeCallbacks(progressRunnable);
        if (mediaController != null) {
            mediaController.removeListener(playerListener);
            mediaController = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }

    private void syncMetadata() {
        if (mediaController == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item != null) {
            tvTitle.setText(item.mediaMetadata.title != null ? item.mediaMetadata.title : "Unknown Track");
            tvArtist.setText(item.mediaMetadata.artist != null ? item.mediaMetadata.artist : "Unknown Artist");
            
            // Try to load lyrics
            if (item.mediaId != null) {
                 Song temp = new Song("", "", Uri.parse(item.mediaId), 0);
                 SongMetadataManager.applySavedEdits(this, temp);
                 TextView tvLyrics = findViewById(R.id.tv_scrolled_lyrics);
                 if (tvLyrics != null) {
                     String lyrics = temp.getLyrics();
                     if (lyrics != null && !lyrics.trim().isEmpty()) {
                         tvLyrics.setText(lyrics);
                         tvLyrics.setVisibility(View.VISIBLE);
                     } else {
                         tvLyrics.setText("No synchronized lyrics found.\nTap 'LYRICS / EDIT' below to paste or load an .lrc file.");
                         tvLyrics.setVisibility(View.VISIBLE);
                     }
                 }
            }
            
            if (item.mediaMetadata.artworkUri != null) {
                try {
                    ivArt.setImageURI(item.mediaMetadata.artworkUri);
                    if (ivArt.getDrawable() == null) {
                        ivArt.setImageResource(R.drawable.ic_album_placeholder);
                    } else {
                        extractPalette(item.mediaMetadata.artworkUri);
                    }
                } catch (Exception e) {
                    ivArt.setImageResource(R.drawable.ic_album_placeholder);
                }
            } else {
                ivArt.setImageResource(R.drawable.ic_album_placeholder);
                resetPalette();
            }
        }
        syncProgress();
        syncFavoriteState();
    }

    private void extractPalette(Uri artUri) {
        try (InputStream is = getContentResolver().openInputStream(artUri)) {
            if (is == null) return;
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                Palette.from(bitmap).generate(palette -> {
                    if (palette != null && !isDestroyed()) {
                        int defaultColor = ContextCompat.getColor(this, R.color.accent_blue);
                        int color = palette.getVibrantColor(palette.getDominantColor(defaultColor));
                        applyThemeColor(color);
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }
    
    private void resetPalette() {
        applyThemeColor(ContextCompat.getColor(this, R.color.accent_blue));
    }
    
    private void applyThemeColor(int color) {
        btnPlayPause.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(color));
        
        // Darken text for badges to ensure contrast against potential bright backgrounds
        int textColor = ContextCompat.getColor(this, R.color.text_primary);
        ((TextView) findViewById(R.id.tv_lossless_badge)).setTextColor(textColor);
        
        if (mediaController != null) {
            syncFavoriteState(color);
            syncShuffleState(color);
            syncRepeatState(color);
        }
    }
    
    private void syncFavoriteState() {
        syncFavoriteState(ContextCompat.getColor(this, R.color.accent_blue));
    }

    private void syncFavoriteState(int activeColor) {
        if (mediaController == null || btnFav == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item != null && !item.mediaId.isEmpty()) {
            boolean isFav = SongMetadataManager.isFavorite(this, item.mediaId);
            btnFav.setIconResource(isFav ? R.drawable.ic_favorite_24 : R.drawable.ic_favorite_border_24);
            btnFav.setIconTintResource(isFav ? activeColor : R.color.text_secondary);
        }
    }

    private void syncPlayPauseState() {
        if (mediaController == null || btnPlayPause == null) return;
        btnPlayPause.setImageResource(mediaController.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
    }

    private void syncShuffleState() {
        syncShuffleState(ContextCompat.getColor(this, R.color.accent_blue));
    }

    private void syncShuffleState(int activeColor) {
        if (mediaController == null || btnShuffle == null) return;
        btnShuffle.setIconTintResource(mediaController.getShuffleModeEnabled() ? activeColor : R.color.text_secondary);
    }

    private void syncRepeatState() {
        syncRepeatState(ContextCompat.getColor(this, R.color.accent_blue));
    }

    private void syncRepeatState(int activeColor) {
        if (mediaController == null || btnRepeat == null) return;
        int mode = mediaController.getRepeatMode();
        btnRepeat.setIconTintResource(mode != Player.REPEAT_MODE_OFF ? activeColor : R.color.text_secondary);
    }

    private void syncProgress() {
        if (mediaController == null || isTracking) return;
        long pos = mediaController.getCurrentPosition();
        long dur = mediaController.getDuration();
        if (dur > 0) {
            seekBar.setProgress((int) ((pos * 1000L) / dur));
            tvTimeCurrent.setText(formatTime(pos));
            tvTimeRemaining.setText("-" + formatTime(dur - pos));
        }
    }

    private String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}