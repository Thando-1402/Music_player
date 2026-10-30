package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.palette.graphics.Palette;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NowPlayingActivity extends AppCompatActivity {

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

    private TextView tvTitle, tvArtist, tvTimeCurrent, tvTimeRemaining;
    private TextView tvDeviceName, tvDeviceCodec;
    private TextView tvLyricActive, tvLyricNext, tvScrolledLyrics;
    private View lyricsScrollContainer, lyricsLiveCard;
    private ImageView ivArt, ivBackdropArt;
    private FloatingActionButton btnPlayPause;
    private MaterialButton btnPrev, btnNext, btnFav, btnShuffle, btnRepeat, btnLyricsSheet;
    private SeekBar seekBar;
    private boolean isTracking = false;
    private int currentAccentColor;
    private AudioDeviceCallback audioDeviceCallback;

    private static class LrcLine {
        final long timeMs;
        final String text;
        LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private final List<LrcLine> parsedLrcLines = new ArrayList<>();
    private final List<String> plainLyricLines = new ArrayList<>();

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            syncProgress();
            progressHandler.postDelayed(this, 350);
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
            syncShuffleState(currentAccentColor);
        }
        @Override
        public void onRepeatModeChanged(int repeatMode) {
            syncRepeatState(currentAccentColor);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        currentAccentColor = ContextCompat.getColor(this, R.color.accent_blue);

        tvTitle = findViewById(R.id.tv_track_title);
        tvTitle.setSelected(true);
        tvArtist = findViewById(R.id.tv_track_artist_album);
        tvTimeCurrent = findViewById(R.id.tv_time_current);
        tvTimeRemaining = findViewById(R.id.tv_time_remaining);
        tvDeviceName = findViewById(R.id.tv_connected_device_name);
        tvDeviceCodec = findViewById(R.id.tv_connected_device_codec);
        tvLyricActive = findViewById(R.id.tv_lyric_active_line);
        tvLyricNext = findViewById(R.id.tv_lyric_next_line);
        tvScrolledLyrics = findViewById(R.id.tv_scrolled_lyrics);
        lyricsScrollContainer = findViewById(R.id.lyrics_scroll_container);
        lyricsLiveCard = findViewById(R.id.lyrics_live_card);
        ivArt = findViewById(R.id.iv_hero_album_art);
        ivBackdropArt = findViewById(R.id.iv_fullscreen_backdrop_art);
        seekBar = findViewById(R.id.slider_playback_progress);

        tvTitle.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        tvArtist.setShadowLayer(6f, 0f, 2f, Color.BLACK);

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

        btnPrev = findViewById(R.id.btn_skip_previous);
        btnPrev.setOnClickListener(v -> {
            if (mediaController != null) mediaController.seekToPrevious();
        });

        btnNext = findViewById(R.id.btn_skip_next);
        btnNext.setOnClickListener(v -> {
            if (mediaController != null) mediaController.seekToNext();
        });

        btnShuffle = findViewById(R.id.btn_toggle_shuffle);
        btnShuffle.setOnClickListener(v -> {
            if (mediaController != null) {
                boolean next = !mediaController.getShuffleModeEnabled();
                mediaController.setShuffleModeEnabled(next);
                syncShuffleState(currentAccentColor);
                Toast.makeText(this, next ? "Shuffle On" : "Shuffle Off", Toast.LENGTH_SHORT).show();
            }
        });

        btnRepeat = findViewById(R.id.btn_toggle_repeat);
        btnRepeat.setOnClickListener(v -> {
            if (mediaController != null) {
                int mode = mediaController.getRepeatMode();
                int nextMode = mode == Player.REPEAT_MODE_OFF
                        ? Player.REPEAT_MODE_ALL
                        : (mode == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                mediaController.setRepeatMode(nextMode);
                syncRepeatState(currentAccentColor);
                String label = nextMode == Player.REPEAT_MODE_ALL ? "Repeat All"
                        : (nextMode == Player.REPEAT_MODE_ONE ? "Repeat One" : "Repeat Off");
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            }
        });

        btnLyricsSheet = findViewById(R.id.btn_lyrics_sheet);
        View.OnClickListener toggleLyricsAction = v -> {
            if (parsedLrcLines.isEmpty() && plainLyricLines.isEmpty()) {
                openTrackEditorForCurrentSong();
                return;
            }
            if (lyricsScrollContainer != null) {
                boolean show = lyricsScrollContainer.getVisibility() != View.VISIBLE;
                lyricsScrollContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        };
        btnLyricsSheet.setOnClickListener(toggleLyricsAction);
        if (lyricsLiveCard != null) {
            lyricsLiveCard.setOnClickListener(toggleLyricsAction);
        }
        btnLyricsSheet.setOnLongClickListener(v -> {
            openTrackEditorForCurrentSong();
            return true;
        });

        btnFav = findViewById(R.id.btn_toggle_favorite);
        if (btnFav != null) {
            btnFav.setOnClickListener(v -> {
                if (mediaController != null) {
                    MediaItem item = mediaController.getCurrentMediaItem();
                    if (item != null && !item.mediaId.isEmpty()) {
                        boolean newFav = !SongMetadataManager.isFavorite(this, item.mediaId);
                        SongMetadataManager.setFavorite(this, item.mediaId, newFav);
                        syncFavoriteState(currentAccentColor);
                        Toast.makeText(this, newFav ? "Added to Favourites ♥" : "Removed from Favourites", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        findViewById(R.id.card_audio_route_output).setOnClickListener(v -> {
            PlaybackStateHelper.updateOutputLabels(this, tvDeviceName, tvDeviceCodec);
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
                        updateSynchronizedLyrics(newPos, duration);
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
    }

    private void openTrackEditorForCurrentSong() {
        if (mediaController == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item == null || item.mediaId.isEmpty()) return;
        Intent editIntent = new Intent(this, EditTrackActivity.class);
        editIntent.putExtra("EXTRA_SONG_URI", item.mediaId);
        editIntent.putExtra("EXTRA_SONG_TITLE", item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "");
        editIntent.putExtra("EXTRA_SONG_ARTIST", item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "");
        startActivity(editIntent);
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
                syncShuffleState(currentAccentColor);
                syncRepeatState(currentAccentColor);
                if (mediaController.isPlaying()) progressHandler.post(progressRunnable);
            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mediaController != null) {
            syncMetadata();
        }
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
        if (item != null && !item.mediaId.isEmpty()) {
            String rawTitle = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "Unknown Track";
            String rawArtist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "Unknown Artist";
            Song temp = new Song(rawTitle, rawArtist, Uri.parse(item.mediaId), 0);
            SongMetadataManager.applySavedEdits(this, temp);

            tvTitle.setText(temp.getTitle());
            tvArtist.setText(temp.getArtist());

            loadAndParseLyrics(temp.getLyrics());

            Uri coverUri = temp.getCustomCoverUri() != null ? temp.getCustomCoverUri() : item.mediaMetadata.artworkUri;
            if (coverUri != null) {
                try {
                    ivArt.setImageURI(coverUri);
                    if (ivArt.getDrawable() == null) {
                        ivArt.setImageResource(R.drawable.ic_album_placeholder_large);
                        if (ivBackdropArt != null) ivBackdropArt.setImageResource(R.drawable.ic_album_placeholder);
                        resetPalette();
                    } else {
                        if (ivBackdropArt != null) ivBackdropArt.setImageURI(coverUri);
                        extractPalette(coverUri);
                    }
                } catch (Exception e) {
                    ivArt.setImageResource(R.drawable.ic_album_placeholder_large);
                    if (ivBackdropArt != null) ivBackdropArt.setImageResource(R.drawable.ic_album_placeholder);
                    resetPalette();
                }
            } else {
                ivArt.setImageResource(R.drawable.ic_album_placeholder_large);
                if (ivBackdropArt != null) ivBackdropArt.setImageResource(R.drawable.ic_album_placeholder);
                resetPalette();
            }
        }
        syncProgress();
        syncFavoriteState(currentAccentColor);
    }

    private void loadAndParseLyrics(String rawLyrics) {
        parsedLrcLines.clear();
        plainLyricLines.clear();
        if (rawLyrics == null || rawLyrics.trim().isEmpty()) {
            if (tvLyricActive != null) tvLyricActive.setText("♪ Tap LYRICS below to load .lrc or paste lyrics");
            if (tvLyricNext != null) tvLyricNext.setText("Long-press LYRICS to open Track Editor");
            if (tvScrolledLyrics != null) tvScrolledLyrics.setText("No lyrics saved for this track.");
            return;
        }
        if (tvScrolledLyrics != null) {
            tvScrolledLyrics.setText(rawLyrics.replaceAll("\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?\\]", "").trim());
        }
        Pattern lrcPattern = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)");
        String[] lines = rawLyrics.split("\\r?\\n");
        for (String line : lines) {
            Matcher m = lrcPattern.matcher(line.trim());
            if (m.matches()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String msPart = m.group(3);
                long ms = 0;
                if (msPart != null && !msPart.isEmpty()) {
                    ms = Long.parseLong(msPart) * (msPart.length() == 1 ? 100 : (msPart.length() == 2 ? 10 : 1));
                }
                String text = m.group(4) != null ? m.group(4).trim() : "";
                if (!text.isEmpty()) {
                    parsedLrcLines.add(new LrcLine(min * 60000L + sec * 1000L + ms, text));
                }
            } else if (!line.trim().isEmpty() && !line.trim().startsWith("[")) {
                plainLyricLines.add(line.trim());
            }
        }
    }

    private void updateSynchronizedLyrics(long posMs, long durMs) {
        if (tvLyricActive == null || tvLyricNext == null) return;
        if (!parsedLrcLines.isEmpty()) {
            int activeIdx = 0;
            for (int i = 0; i < parsedLrcLines.size(); i++) {
                if (posMs >= parsedLrcLines.get(i).timeMs) {
                    activeIdx = i;
                } else {
                    break;
                }
            }
            tvLyricActive.setText("♪ " + parsedLrcLines.get(activeIdx).text);
            tvLyricActive.setTextColor(currentAccentColor);
            if (activeIdx + 1 < parsedLrcLines.size()) {
                tvLyricNext.setText(parsedLrcLines.get(activeIdx + 1).text);
            } else {
                tvLyricNext.setText("");
            }
        } else if (!plainLyricLines.isEmpty() && durMs > 0) {
            int idx = (int) ((posMs * plainLyricLines.size()) / durMs);
            idx = Math.max(0, Math.min(plainLyricLines.size() - 1, idx));
            tvLyricActive.setText("♪ " + plainLyricLines.get(idx));
            tvLyricActive.setTextColor(currentAccentColor);
            if (idx + 1 < plainLyricLines.size()) {
                tvLyricNext.setText(plainLyricLines.get(idx + 1));
            } else {
                tvLyricNext.setText("");
            }
        }
    }

    private void extractPalette(Uri artUri) {
        try (InputStream is = getContentResolver().openInputStream(artUri)) {
            if (is == null) {
                resetPalette();
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                Palette.from(bitmap).generate(palette -> {
                    if (palette != null && !isDestroyed()) {
                        int defaultColor = ContextCompat.getColor(this, R.color.accent_blue);
                        int raw = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(defaultColor)));
                        int safeColor = PlaybackStateHelper.ensureReadableAccent(raw, defaultColor);
                        applyThemeColor(safeColor);
                    }
                });
            }
        } catch (Exception e) {
            resetPalette();
        }
    }

    private void resetPalette() {
        applyThemeColor(ContextCompat.getColor(this, R.color.accent_blue));
    }

    private void applyThemeColor(int color) {
        currentAccentColor = color;
        ColorStateList accentList = ColorStateList.valueOf(color);

        btnPlayPause.setBackgroundTintList(accentList);
        int fabIconColor = ColorUtils.calculateLuminance(color) > 0.42 ? Color.parseColor("#121316") : Color.WHITE;
        btnPlayPause.setSupportImageTintList(ColorStateList.valueOf(fabIconColor));

        if (btnPrev != null) btnPrev.setIconTint(accentList);
        if (btnNext != null) btnNext.setIconTint(accentList);
        if (btnLyricsSheet != null) btnLyricsSheet.setIconTint(accentList);

        TextView badge = findViewById(R.id.tv_lossless_badge);
        if (badge != null) {
            badge.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }

        seekBar.setProgressTintList(accentList);
        seekBar.setThumbTintList(accentList);

        if (mediaController != null) {
            syncFavoriteState(color);
            syncShuffleState(color);
            syncRepeatState(color);
        }
    }

    private void syncFavoriteState(int activeColor) {
        if (mediaController == null || btnFav == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item != null && !item.mediaId.isEmpty()) {
            boolean isFav = SongMetadataManager.isFavorite(this, item.mediaId);
            btnFav.setIconResource(isFav ? R.drawable.ic_favorite_24 : R.drawable.ic_favorite_border_24);
            int tint = isFav ? activeColor : ContextCompat.getColor(this, R.color.text_secondary);
            btnFav.setIconTint(ColorStateList.valueOf(tint));
        }
    }

    private void syncPlayPauseState() {
        if (mediaController == null || btnPlayPause == null) return;
        btnPlayPause.setImageResource(mediaController.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
    }

    private void syncShuffleState(int activeColor) {
        if (mediaController == null || btnShuffle == null) return;
        int tint = mediaController.getShuffleModeEnabled() ? activeColor : ContextCompat.getColor(this, R.color.text_secondary);
        btnShuffle.setIconTint(ColorStateList.valueOf(tint));
    }

    private void syncRepeatState(int activeColor) {
        if (mediaController == null || btnRepeat == null) return;
        int mode = mediaController.getRepeatMode();
        int tint = mode != Player.REPEAT_MODE_OFF ? activeColor : ContextCompat.getColor(this, R.color.text_secondary);
        btnRepeat.setIconTint(ColorStateList.valueOf(tint));
    }

    private void syncProgress() {
        if (mediaController == null || isTracking) return;
        long pos = mediaController.getCurrentPosition();
        long dur = mediaController.getDuration();
        if (dur > 0) {
            seekBar.setProgress((int) ((pos * 1000L) / dur));
            tvTimeCurrent.setText(formatTime(pos));
            tvTimeRemaining.setText("-" + formatTime(dur - pos));
            updateSynchronizedLyrics(pos, dur);
        }
    }

    private String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
