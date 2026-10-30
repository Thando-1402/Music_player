package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NowPlayingActivity extends AppCompatActivity {

    /**
     * ±200ms fuzzy matching window to compensate for VBR audio drift and lead-in silence.
     */
    private static final long LRC_FUZZY_TOLERANCE_MS = 200L;
    private static final Pattern LRC_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern LRC_OFFSET_TAG_PATTERN = Pattern.compile("(?i)\\[offset:\\s*([+-]?\\d+)\\s*]");

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private ExecutorService ioExecutor;

    private TextView tvTitle, tvArtist, tvTimeCurrent, tvTimeRemaining;
    private TextView tvDeviceName, tvDeviceCodec, badgeDspStatus, badgeLosslessMaster, badgeFlacSpec, badgeAutoDetected;
    private TextView tvLyricActive, tvLyricNext, tvScrolledLyrics;
    private View lyricsScrollContainer;
    private ImageView ivArt, ivBackdropArt, ivRouteIcon;
    private FloatingActionButton btnPlayPause;
    private MaterialButton btnPrev, btnNext, btnFav, btnShuffle, btnRepeat, btnLyricsSheet, btnEqProfile;
    private SeekBar seekBar;

    private boolean isTracking = false;
    private int currentAccentColor;
    private int lastSyncedLrcIndex = -1;
    private long lrcHeaderOffsetMs = 0L;
    private long userCalibrationOffsetMs = 0L;
    private String loadedMediaId = "";
    private String loadedRawLyrics = "";
    private AudioDeviceCallback audioDeviceCallback;

    private static class LrcLine implements Comparable<LrcLine> {
        long timeMs;
        final String text;

        LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        @Override
        public int compareTo(LrcLine other) {
            return Long.compare(this.timeMs, other.timeMs);
        }
    }

    private final List<LrcLine> parsedLrcLines = new ArrayList<>();
    private final List<String> plainLyricLines = new ArrayList<>();

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            syncProgress();
            progressHandler.postDelayed(this, 180L);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            UiAnimationHelper.animateTrackTransition(tvTitle, true);
            syncMetadata(true);
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            syncMetadata(false);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            syncPlayPauseState();
            UiAnimationHelper.animateAlbumArtPlayState(ivArt, isPlaying);
            progressHandler.removeCallbacks(progressRunnable);
            if (isPlaying) {
                progressHandler.post(progressRunnable);
            }
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

        ioExecutor = Executors.newSingleThreadExecutor();
        currentAccentColor = ContextCompat.getColor(this, R.color.accent_blue);

        tvTitle = findViewById(R.id.tv_track_title);
        tvTitle.setSelected(true);
        tvArtist = findViewById(R.id.tv_track_artist_album);
        tvTimeCurrent = findViewById(R.id.tv_time_current);
        tvTimeRemaining = findViewById(R.id.tv_time_remaining);
        tvDeviceName = findViewById(R.id.tv_connected_device_name);
        tvDeviceCodec = findViewById(R.id.tv_connected_device_codec);
        ivRouteIcon = findViewById(R.id.iv_route_type_icon);
        badgeDspStatus = findViewById(R.id.badge_dsp_balanced);
        badgeLosslessMaster = findViewById(R.id.badge_lossless_master);
        badgeFlacSpec = findViewById(R.id.badge_flac_spec);
        badgeAutoDetected = findViewById(R.id.badge_auto_detected);
        tvLyricActive = findViewById(R.id.tv_lyric_active_line);
        tvLyricNext = findViewById(R.id.tv_lyric_next_line);
        tvScrolledLyrics = findViewById(R.id.tv_scrolled_lyrics);
        lyricsScrollContainer = findViewById(R.id.lyrics_scroll_container);
        View lyricsLiveCard = findViewById(R.id.lyrics_live_card);
        ivArt = findViewById(R.id.iv_hero_album_art);
        ivBackdropArt = findViewById(R.id.iv_fullscreen_backdrop_art);
        seekBar = findViewById(R.id.slider_playback_progress);

        tvTitle.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        tvArtist.setShadowLayer(6f, 0f, 2f, Color.BLACK);

        View btnCollapse = findViewById(R.id.btn_collapse);
        btnCollapse.setOnClickListener(v -> UiAnimationHelper.closeNowPlaying(this));

        View btnQueue = findViewById(R.id.btn_queue);
        if (btnQueue != null) {
            btnQueue.setOnClickListener(v ->
                    startActivity(new Intent(NowPlayingActivity.this, PlaylistsActivity.class)));
        }

        View btnOverflow = findViewById(R.id.btn_now_playing_overflow);
        if (btnOverflow != null) {
            btnOverflow.setOnClickListener(this::showOverflowMenu);
        }

        btnPlayPause = findViewById(R.id.btn_play_pause_fab);
        btnPlayPause.setOnClickListener(v -> {
            if (mediaController != null) {
                if (mediaController.isPlaying()) mediaController.pause();
                else mediaController.play();
                btnPlayPause.animate().cancel();
                btnPlayPause.animate()
                        .scaleX(0.86f)
                        .scaleY(0.86f)
                        .setDuration(85)
                        .setInterpolator(UiAnimationHelper.DECELERATE)
                        .withLayer()
                        .withEndAction(() -> btnPlayPause.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(190)
                                .setInterpolator(UiAnimationHelper.OVERSHOOT)
                                .withLayer()
                                .start())
                        .start();
            }
        });

        btnPrev = findViewById(R.id.btn_skip_previous);
        btnPrev.setOnClickListener(v -> {
            if (mediaController != null) {
                UiAnimationHelper.animateTrackTransition(tvTitle, false);
                mediaController.seekToPrevious();
            }
        });

        btnNext = findViewById(R.id.btn_skip_next);
        btnNext.setOnClickListener(v -> {
            if (mediaController != null) {
                UiAnimationHelper.animateTrackTransition(tvTitle, true);
                mediaController.seekToNext();
            }
        });

        btnShuffle = findViewById(R.id.btn_toggle_shuffle);
        btnShuffle.setOnClickListener(v -> {
            if (mediaController != null) {
                boolean next = !mediaController.getShuffleModeEnabled();
                mediaController.setShuffleModeEnabled(next);
                UiAnimationHelper.pulseBounce(btnShuffle);
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
                UiAnimationHelper.pulseBounce(btnRepeat);
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
                if (show) {
                    lyricsScrollContainer.setAlpha(0f);
                    lyricsScrollContainer.setVisibility(View.VISIBLE);
                    lyricsScrollContainer.animate()
                            .alpha(1f)
                            .setDuration(180)
                            .setInterpolator(UiAnimationHelper.DECELERATE)
                            .withLayer()
                            .start();
                } else {
                    lyricsScrollContainer.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .setInterpolator(UiAnimationHelper.DECELERATE)
                            .withLayer()
                            .withEndAction(() -> lyricsScrollContainer.setVisibility(View.GONE))
                            .start();
                }
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

        btnEqProfile = findViewById(R.id.btn_eq_profile);
        if (btnEqProfile != null) {
            btnEqProfile.setOnClickListener(v ->
                    startActivity(new Intent(NowPlayingActivity.this, EqualizerActivity.class)));
        }

        btnFav = findViewById(R.id.btn_toggle_favorite);
        if (btnFav != null) {
            btnFav.setOnClickListener(v -> {
                if (mediaController != null) {
                    MediaItem item = mediaController.getCurrentMediaItem();
                    if (item != null && !item.mediaId.isEmpty()) {
                        final String mediaId = item.mediaId;
                        final android.content.Context appCtx = getApplicationContext();
                        ioExecutor.execute(() -> {
                            final boolean newFav = !SongMetadataManager.isFavorite(appCtx, mediaId);
                            SongMetadataManager.setFavorite(appCtx, mediaId, newFav);
                            progressHandler.post(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                UiAnimationHelper.pulseBounce(btnFav);
                                syncFavoriteState(currentAccentColor);
                                Toast.makeText(this, newFav ? "Added to Favourites ♥" : "Removed from Favourites", Toast.LENGTH_SHORT).show();
                            });
                        });
                    }
                }
            });
        }

        View.OnClickListener routeClickListener = v -> AudioRouteHelper.showAudioOutputSwitcher(this);
        View cardRoute = findViewById(R.id.card_audio_route_output);
        if (cardRoute != null) cardRoute.setOnClickListener(routeClickListener);
        View btnSwitchRoute = findViewById(R.id.btn_switch_audio_route);
        if (btnSwitchRoute != null) btnSwitchRoute.setOnClickListener(routeClickListener);

        UiAnimationHelper.attachSpringTouch(btnPrev, btnNext, btnLyricsSheet, btnEqProfile);
        UiAnimationHelper.runStaggeredEntrance(
                lyricsLiveCard,
                findViewById(R.id.badges_container),
                findViewById(R.id.metadata_layout),
                findViewById(R.id.seek_bar_container),
                findViewById(R.id.primary_controls_layout),
                findViewById(R.id.secondary_pills_layout),
                cardRoute
        );

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaController != null) {
                    long duration = mediaController.getDuration();
                    if (duration > 0) {
                        long newPos = (duration * progress) / 1000L;
                        tvTimeCurrent.setText(formatTime(newPos));
                        tvTimeRemaining.setText(getString(R.string.time_remaining_fmt, formatTime(duration - newPos)));
                        lastSyncedLrcIndex = -1;
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
                        lastSyncedLrcIndex = -1;
                        mediaController.seekTo((duration * seekBar.getProgress()) / 1000L);
                    }
                }
                isTracking = false;
            }
        });
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.now_playing_overflow_menu);
        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.action_np_edit_track) {
                openTrackEditorForCurrentSong();
                return true;
            } else if (id == R.id.action_np_ai_sync_lyrics) {
                aiSyncCurrentSongLyrics();
                return true;
            } else if (id == R.id.action_np_lyrics_earlier) {
                adjustLyricsOffset(-500L);
                return true;
            } else if (id == R.id.action_np_lyrics_later) {
                adjustLyricsOffset(+500L);
                return true;
            } else if (id == R.id.action_np_calibrate_lyrics) {
                showLyricsSyncCalibrationDialog();
                return true;
            } else if (id == R.id.action_np_restore_lyrics) {
                restoreCurrentSongBackupLyrics();
                return true;
            } else if (id == R.id.action_np_ai_clean) {
                aiCleanCurrentSong();
                return true;
            } else if (id == R.id.action_np_equalizer) {
                startActivity(new Intent(this, EqualizerActivity.class));
                return true;
            } else if (id == R.id.action_np_playlists) {
                startActivity(new Intent(this, PlaylistsActivity.class));
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void adjustLyricsOffset(long deltaMs) {
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) return;
        final String mediaId = mediaController.getCurrentMediaItem().mediaId;
        if (mediaId.isEmpty()) return;
        userCalibrationOffsetMs += deltaMs;
        final long newOffset = userCalibrationOffsetMs;
        final android.content.Context appCtx = getApplicationContext();
        ioExecutor.execute(() -> SongMetadataManager.setLyricsOffsetMs(appCtx, mediaId, newOffset));
        lastSyncedLrcIndex = -1;
        syncProgress();
        String sign = userCalibrationOffsetMs > 0 ? "+" : "";
        Toast.makeText(this, "Lyrics timing offset: " + sign + userCalibrationOffsetMs + "ms", Toast.LENGTH_SHORT).show();
    }

    private void aiSyncCurrentSongLyrics() {
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) return;
        final String mediaId = mediaController.getCurrentMediaItem().mediaId;
        if (mediaId.isEmpty()) return;

        final String curTitle = tvTitle != null ? tvTitle.getText().toString() : "Track";
        final String curArtist = tvArtist != null ? tvArtist.getText().toString() : "Artist";
        final long durMs = Math.max(0L, mediaController.getDuration());
        final android.content.Context appCtx = getApplicationContext();

        Toast.makeText(this, "✨ Syncing & formatting lyrics...", Toast.LENGTH_SHORT).show();
        OpenRouterAiHelper.autoSyncAndFormatLyrics(this, curTitle, curArtist, durMs, loadedRawLyrics, (syncedLrc, usedCloudAi) -> {
            if (isFinishing() || isDestroyed()) return;
            if (syncedLrc == null || syncedLrc.trim().isEmpty()) {
                Toast.makeText(this, "No lyrics found to sync — open Edit Track to paste or import lyrics first.", Toast.LENGTH_LONG).show();
                return;
            }
            ioExecutor.execute(() -> {
                Song temp = new Song(curTitle, curArtist, Uri.parse(mediaId), durMs);
                SongMetadataManager.applySavedEdits(appCtx, temp, false);
                SongMetadataManager.saveSongEditFull(
                        appCtx,
                        temp,
                        temp.getTitle(),
                        temp.getArtist(),
                        temp.getAlbum(),
                        temp.getTrackNumber(),
                        temp.getYearGenre(),
                        temp.getCustomCoverUri(),
                        syncedLrc
                );
                SongMetadataManager.setLyricsOffsetMs(appCtx, mediaId, 0L);
                NavigationHelper.markLibraryDirty();
                progressHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    userCalibrationOffsetMs = 0L;
                    loadedRawLyrics = syncedLrc;
                    loadAndParseLyrics(syncedLrc, durMs);
                    syncProgress();
                    Toast.makeText(NowPlayingActivity.this,
                            usedCloudAi
                                    ? "✨ AI synced lyrics saved to Lyrics_Synced/ (backup preserved)!"
                                    : "✨ Universal LRC rescaled & saved to Lyrics_Synced/!",
                            Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    private void restoreCurrentSongBackupLyrics() {
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) return;
        final String mediaId = mediaController.getCurrentMediaItem().mediaId;
        if (mediaId.isEmpty()) return;
        final String curTitle = tvTitle != null ? tvTitle.getText().toString() : "";
        final String curArtist = tvArtist != null ? tvArtist.getText().toString() : "";
        final long durMs = mediaController.getDuration();
        final android.content.Context appCtx = getApplicationContext();

        ioExecutor.execute(() -> {
            final String restored = SongMetadataManager.restoreOriginalBackupLyrics(appCtx, mediaId, curTitle, curArtist);
            progressHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (restored != null && !restored.isEmpty()) {
                    userCalibrationOffsetMs = 0L;
                    loadedRawLyrics = restored;
                    loadAndParseLyrics(restored, durMs);
                    syncProgress();
                    Toast.makeText(NowPlayingActivity.this, "Original backup lyrics restored!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(NowPlayingActivity.this, "No original backup lyrics found for this track", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void aiCleanCurrentSong() {
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        final String mediaId = item.mediaId;
        if (mediaId.isEmpty()) return;

        String curTitle = tvTitle != null ? tvTitle.getText().toString() : "";
        String curArtist = tvArtist != null ? tvArtist.getText().toString() : "";
        final android.content.Context appCtx = getApplicationContext();

        Toast.makeText(this, "✨ Cleaning track metadata...", Toast.LENGTH_SHORT).show();
        OpenRouterAiHelper.autoCleanSongMetadata(this, curTitle, curArtist, (cleanTitle, cleanArtist, usedCloudAi) -> {
            if (isFinishing() || isDestroyed()) return;
            ioExecutor.execute(() -> {
                Song temp = new Song(cleanTitle, cleanArtist, Uri.parse(mediaId), 0);
                SongMetadataManager.applySavedEdits(appCtx, temp, true);
                SongMetadataManager.saveSongEdit(appCtx, temp, cleanTitle, cleanArtist, temp.getCustomCoverUri(), temp.getLyrics());
                NavigationHelper.markLibraryDirty();
                progressHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvTitle.setText(cleanTitle);
                    tvArtist.setText(cleanArtist);
                    Toast.makeText(NowPlayingActivity.this,
                            (usedCloudAi ? "✨ AI Cleaned: " : "✨ Cleaned: ") + cleanTitle,
                            Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    private void showLyricsSyncCalibrationDialog() {
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) return;
        final String mediaId = mediaController.getCurrentMediaItem().mediaId;
        final android.content.Context appCtx = getApplicationContext();
        final long[] offsets = new long[]{-500L, -200L, 0L, 200L, 500L};
        final String[] labels = new String[]{
                "Earlier by 500ms (-500ms)",
                "Earlier by 200ms (-200ms)",
                "Default (0ms • ±200ms Auto VBR Tolerance)",
                "Later by 200ms (+200ms)",
                "Later by 500ms (+500ms)"
        };
        int checked = 2;
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] == userCalibrationOffsetMs) {
                checked = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_calibrate_lyrics_sync)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    userCalibrationOffsetMs = offsets[which];
                    final long chosenOffset = userCalibrationOffsetMs;
                    ioExecutor.execute(() -> SongMetadataManager.setLyricsOffsetMs(appCtx, mediaId, chosenOffset));
                    lastSyncedLrcIndex = -1;
                    syncProgress();
                    dialog.dismiss();
                    Toast.makeText(this, "Lyrics sync offset: " + offsets[which] + "ms", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openTrackEditorForCurrentSong() {
        if (mediaController == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item == null || item.mediaId.isEmpty()) return;
        Intent editIntent = new Intent(this, EditTrackActivity.class);
        editIntent.putExtra("EXTRA_SONG_URI", item.mediaId);
        editIntent.putExtra("EXTRA_SONG_TITLE", tvTitle != null ? tvTitle.getText().toString() : "");
        editIntent.putExtra("EXTRA_SONG_ARTIST", tvArtist != null ? tvArtist.getText().toString() : "");
        startActivity(editIntent);
        NavigationHelper.applySlideTransition(this, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AudioRouteHelper.bindRouteViews(this, tvDeviceName, tvDeviceCodec, ivRouteIcon);
        audioDeviceCallback = AudioRouteHelper.registerLiveRouteCallback(this,
                () -> AudioRouteHelper.bindRouteViews(this, tvDeviceName, tvDeviceCodec, ivRouteIcon));

        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                mediaController.addListener(playerListener);
                syncMetadata(true);
                syncPlayPauseState();
                syncShuffleState(currentAccentColor);
                syncRepeatState(currentAccentColor);
                if (mediaController.isPlaying()) {
                    progressHandler.post(progressRunnable);
                }
            } catch (Exception ignored) {
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDspBadge();
        if (mediaController != null) {
            syncMetadata(true);
        }
    }

    private void refreshDspBadge() {
        SharedPreferences prefs = getSharedPreferences(OpenRouterAiHelper.PREFS_NAME, MODE_PRIVATE);
        boolean eqEnabled = prefs.getBoolean("eq_master_enabled", true);
        String preset = prefs.getString("eq_preset_name", "BALANCED");
        if (badgeDspStatus != null) {
            badgeDspStatus.setText(eqEnabled ? "DSP: " + preset.toUpperCase(Locale.US) : "DSP: BYPASS");
        }
        if (btnEqProfile != null) {
            btnEqProfile.setText(eqEnabled ? "EQ: " + preset : "EQ Off");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        AudioRouteHelper.unregisterLiveRouteCallback(this, audioDeviceCallback);
        audioDeviceCallback = null;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
    }

    private void syncMetadata(boolean forceReloadAssets) {
        if (mediaController == null) return;
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item == null || item.mediaId.isEmpty()) return;

        final String mediaId = item.mediaId;
        final boolean trackChanged = !mediaId.equals(loadedMediaId);
        loadedMediaId = mediaId;
        if (trackChanged) {
            lastSyncedLrcIndex = -1;
        }

        final String rawTitle = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "Unknown Track";
        final String rawArtist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "Unknown Artist";
        final Uri metadataArtUri = item.mediaMetadata.artworkUri;
        final long currentDurationMs = Math.max(0L, mediaController.getDuration());

        if (!forceReloadAssets && !trackChanged) {
            syncProgress();
            syncFavoriteState(currentAccentColor);
            return;
        }

        ioExecutor.execute(() -> {
            Uri audioUri = Uri.parse(mediaId);
            Song temp = new Song(rawTitle, rawArtist, audioUri, currentDurationMs);
            SongMetadataManager.applySavedEdits(getApplicationContext(), temp, true);
            long savedOffset = SongMetadataManager.getLyricsOffsetMs(getApplicationContext(), mediaId);
            String[] formatBadges = detectAudioFormatBadges(audioUri);

            Uri coverUri = temp.getCustomCoverUri() != null ? temp.getCustomCoverUri() : metadataArtUri;
            String cacheKey = mediaId + (coverUri != null ? "|" + coverUri : "");
            Bitmap artBitmap = ArtworkColorHelper.loadArtworkBitmap(getApplicationContext(), coverUri, audioUri, 512);
            int accent = ArtworkColorHelper.resolveAccentColor(cacheKey, artBitmap, getApplicationContext());

            progressHandler.post(() -> {
                if (isFinishing() || isDestroyed() || !mediaId.equals(loadedMediaId)) return;

                String displayArtist = temp.getAlbum().isEmpty()
                        ? temp.getArtist()
                        : temp.getArtist() + " • " + temp.getAlbum();
                tvTitle.setText(temp.getTitle());
                tvArtist.setText(displayArtist);
                userCalibrationOffsetMs = savedOffset;
                loadedRawLyrics = temp.getLyrics();

                if (badgeFlacSpec != null) badgeFlacSpec.setText(formatBadges[0]);
                if (badgeLosslessMaster != null) badgeLosslessMaster.setText(formatBadges[1]);

                long dur = mediaController != null && mediaController.getDuration() > 0
                        ? mediaController.getDuration() : currentDurationMs;
                loadAndParseLyrics(loadedRawLyrics, dur);

                if (artBitmap != null) {
                    UiAnimationHelper.crossfadeBitmap(ivArt, artBitmap);
                    if (ivBackdropArt != null) ivBackdropArt.setImageBitmap(artBitmap);
                    applyThemeColor(PlaybackStateHelper.ensureReadableAccent(
                            accent, ContextCompat.getColor(NowPlayingActivity.this, R.color.accent_blue)));
                } else {
                    ivArt.setImageResource(R.drawable.ic_album_placeholder_large);
                    if (ivBackdropArt != null) ivBackdropArt.setImageResource(R.drawable.ic_album_placeholder_large);
                    resetPalette();
                }

                syncProgress();
                syncFavoriteState(currentAccentColor);
            });
        });
    }

    private String[] detectAudioFormatBadges(Uri audioUri) {
        String headerSpec = getString(R.string.badge_flac_default);
        String masterBadge = getString(R.string.badge_lossless_master);
        try (Cursor c = getContentResolver().query(audioUri,
                new String[]{MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String mime = c.getString(0) != null ? c.getString(0).toLowerCase(Locale.US) : "";
                String name = c.getString(1) != null ? c.getString(1).toLowerCase(Locale.US) : "";
                if (mime.contains("flac") || name.endsWith(".flac")) {
                    headerSpec = "● FLAC 24-BIT HI-RES";
                    masterBadge = "LOSSLESS FLAC";
                } else if (mime.contains("wav") || name.endsWith(".wav")) {
                    headerSpec = "● PCM WAV UNCOMPRESSED";
                    masterBadge = "LOSSLESS WAV";
                } else if (mime.contains("mp4") || mime.contains("m4a") || name.endsWith(".m4a") || name.endsWith(".aac")) {
                    headerSpec = "● AAC / ALAC CONTAINER";
                    masterBadge = "AAC HI-FI";
                } else if (mime.contains("ogg") || mime.contains("opus") || name.endsWith(".ogg") || name.endsWith(".opus")) {
                    headerSpec = "● VORBIS / OPUS STREAM";
                    masterBadge = "OGG / OPUS";
                } else if (mime.contains("mpeg") || name.endsWith(".mp3")) {
                    headerSpec = "● MPEG LAYER-3 AUDIO";
                    masterBadge = "HQ MP3";
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{headerSpec, masterBadge};
    }

    private void loadAndParseLyrics(String rawLyrics, long trackDurationMs) {
        parsedLrcLines.clear();
        plainLyricLines.clear();
        lrcHeaderOffsetMs = 0L;
        lastSyncedLrcIndex = -1;

        if (rawLyrics != null && !rawLyrics.trim().isEmpty()) {
            String[] lines = rawLyrics.split("\\r?\\n");
            StringBuilder fullTextBuilder = new StringBuilder();

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                Matcher offsetMatcher = LRC_OFFSET_TAG_PATTERN.matcher(line);
                if (offsetMatcher.matches()) {
                    try {
                        String rawOffset = offsetMatcher.group(1);
                        if (rawOffset != null) {
                            lrcHeaderOffsetMs = Long.parseLong(rawOffset);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    continue;
                }

                Matcher timeMatcher = LRC_TIMESTAMP_PATTERN.matcher(line);
                List<Long> timestamps = new ArrayList<>();
                int lastMatchEnd = 0;
                while (timeMatcher.find()) {
                    String g1 = timeMatcher.group(1);
                    String g2 = timeMatcher.group(2);
                    if (g1 == null || g2 == null) continue;
                    long min = Long.parseLong(g1);
                    long sec = Long.parseLong(g2);
                    String frac = timeMatcher.group(3);
                    long ms = 0L;
                    if (frac != null && !frac.isEmpty()) {
                        ms = Long.parseLong(frac) * (frac.length() == 1 ? 100L : (frac.length() == 2 ? 10L : 1L));
                    }
                    timestamps.add(min * 60_000L + sec * 1_000L + ms);
                    lastMatchEnd = timeMatcher.end();
                }

                if (!timestamps.isEmpty()) {
                    String lyricText = line.substring(lastMatchEnd).trim();
                    if (!lyricText.isEmpty()) {
                        for (Long ts : timestamps) {
                            parsedLrcLines.add(new LrcLine(ts, lyricText));
                        }
                        fullTextBuilder.append(lyricText).append('\n');
                    }
                } else if (!line.startsWith("[")) {
                    plainLyricLines.add(line);
                    fullTextBuilder.append(line).append('\n');
                }
            }

            Collections.sort(parsedLrcLines);

            // Auto-rescale drifted/radio-edit LRC files whose timestamps exceed actual track duration
            if (!parsedLrcLines.isEmpty() && trackDurationMs > 15_000L) {
                long maxTs = parsedLrcLines.get(parsedLrcLines.size() - 1).timeMs;
                if (maxTs > trackDurationMs + 5_000L) {
                    double scale = (double) (trackDurationMs - 3_000L) / (double) maxTs;
                    for (LrcLine l : parsedLrcLines) {
                        l.timeMs = Math.max(0L, Math.round(l.timeMs * scale));
                    }
                }
            }

            if (tvScrolledLyrics != null) {
                String built = fullTextBuilder.toString().trim();
                tvScrolledLyrics.setText(built.isEmpty() ? getString(R.string.no_lyrics_loaded) : built);
            }
        } else {
            if (tvScrolledLyrics != null) {
                tvScrolledLyrics.setText(R.string.no_lyrics_loaded);
            }
        }

        if (parsedLrcLines.isEmpty() && plainLyricLines.isEmpty()) {
            if (tvLyricActive != null) {
                tvLyricActive.setText(R.string.lyrics_hint_active);
                tvLyricActive.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            }
            if (tvLyricNext != null) {
                tvLyricNext.setText(R.string.lyrics_hint_next);
            }
        }
    }

    /**
     * Synchronizes .lrc timestamps against mediaController.getCurrentPosition() using a ±200ms
     * fuzzy matching tolerance window to absorb VBR seek/frame drift and lead-in silence.
     */
    private void updateSynchronizedLyrics(long rawPositionMs, long durMs) {
        if (tvLyricActive == null || tvLyricNext == null) return;

        if (!parsedLrcLines.isEmpty()) {
            long effectivePosMs = rawPositionMs - lrcHeaderOffsetMs + userCalibrationOffsetMs;
            long fuzzyMatchCeilingMs = effectivePosMs + LRC_FUZZY_TOLERANCE_MS;

            int candidateIdx = -1;
            for (int i = 0; i < parsedLrcLines.size(); i++) {
                if (fuzzyMatchCeilingMs >= parsedLrcLines.get(i).timeMs) {
                    candidateIdx = i;
                } else {
                    break;
                }
            }

            if (candidateIdx < 0) {
                tvLyricActive.setText(R.string.lyric_intro_ellipsis);
                tvLyricActive.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tvLyricNext.setText(parsedLrcLines.get(0).text);
                lastSyncedLrcIndex = -1;
                return;
            }

            if (lastSyncedLrcIndex >= 0
                    && candidateIdx == lastSyncedLrcIndex - 1
                    && lastSyncedLrcIndex < parsedLrcLines.size()) {
                long prevLineTime = parsedLrcLines.get(lastSyncedLrcIndex).timeMs;
                if (Math.abs(effectivePosMs - prevLineTime) <= LRC_FUZZY_TOLERANCE_MS) {
                    candidateIdx = lastSyncedLrcIndex;
                }
            }

            if (candidateIdx != lastSyncedLrcIndex) {
                lastSyncedLrcIndex = candidateIdx;
                tvLyricActive.setText(getString(R.string.lyric_active_line_fmt, parsedLrcLines.get(candidateIdx).text));
                tvLyricActive.setTextColor(ColorUtils.blendARGB(currentAccentColor, Color.WHITE, 0.35f));
                if (candidateIdx + 1 < parsedLrcLines.size()) {
                    tvLyricNext.setText(parsedLrcLines.get(candidateIdx + 1).text);
                } else {
                    tvLyricNext.setText("");
                }
            }
        } else if (!plainLyricLines.isEmpty() && durMs > 0) {
            long introMs = Math.min(6_000L, durMs / 12L);
            long vocalWindow = Math.max(1L, durMs - introMs * 2L);
            long adjustedPos = Math.max(0L, rawPositionMs + userCalibrationOffsetMs - introMs);
            int idx = (int) ((adjustedPos * plainLyricLines.size()) / vocalWindow);
            idx = Math.max(0, Math.min(plainLyricLines.size() - 1, idx));
            if (idx != lastSyncedLrcIndex) {
                lastSyncedLrcIndex = idx;
                tvLyricActive.setText(getString(R.string.lyric_active_line_fmt, plainLyricLines.get(idx)));
                tvLyricActive.setTextColor(ColorUtils.blendARGB(currentAccentColor, Color.WHITE, 0.35f));
                if (idx + 1 < plainLyricLines.size()) {
                    tvLyricNext.setText(plainLyricLines.get(idx + 1));
                } else {
                    tvLyricNext.setText("");
                }
            }
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
        if (badgeDspStatus != null) badgeDspStatus.setTextColor(color);
        if (badgeFlacSpec != null) badgeFlacSpec.setTextColor(color);
        if (badgeAutoDetected != null) badgeAutoDetected.setTextColor(color);
        if (ivRouteIcon != null) ivRouteIcon.setImageTintList(accentList);

        if (badgeLosslessMaster != null) {
            badgeLosslessMaster.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }

        seekBar.setProgressTintList(accentList);
        seekBar.setThumbTintList(accentList);

        if (!parsedLrcLines.isEmpty() || !plainLyricLines.isEmpty()) {
            tvLyricActive.setTextColor(ColorUtils.blendARGB(color, Color.WHITE, 0.35f));
        }

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
            final String mediaId = item.mediaId;
            final android.content.Context appCtx = getApplicationContext();
            ioExecutor.execute(() -> {
                final boolean isFav = SongMetadataManager.isFavorite(appCtx, mediaId);
                progressHandler.post(() -> {
                    if (isFinishing() || isDestroyed() || btnFav == null) return;
                    btnFav.setIconResource(isFav ? R.drawable.ic_favorite_24 : R.drawable.ic_favorite_border_24);
                    int tint = isFav ? activeColor : ContextCompat.getColor(NowPlayingActivity.this, R.color.text_secondary);
                    btnFav.setIconTint(ColorStateList.valueOf(tint));
                });
            });
        }
    }

    private void syncPlayPauseState() {
        if (mediaController == null || btnPlayPause == null) return;
        btnPlayPause.setImageResource(mediaController.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_36);
    }

    private void syncShuffleState(int activeColor) {
        if (mediaController == null || btnShuffle == null) return;
        int tint = mediaController.getShuffleModeEnabled() ? activeColor : ContextCompat.getColor(this, R.color.text_secondary);
        btnShuffle.setIconTint(ColorStateList.valueOf(tint));
    }

    private void syncRepeatState(int activeColor) {
        if (mediaController == null || btnRepeat == null) return;
        int mode = mediaController.getRepeatMode();
        btnRepeat.setIconResource(mode == Player.REPEAT_MODE_ONE ? R.drawable.ic_repeat_one_24 : R.drawable.ic_repeat_24);
        int tint = mode != Player.REPEAT_MODE_OFF ? activeColor : ContextCompat.getColor(this, R.color.text_secondary);
        btnRepeat.setIconTint(ColorStateList.valueOf(tint));
    }

    private void syncProgress() {
        if (mediaController == null || isTracking) return;
        long pos = Math.max(0L, mediaController.getCurrentPosition());
        long dur = mediaController.getDuration();
        if (dur > 0) {
            seekBar.setProgress((int) ((pos * 1000L) / dur));
            tvTimeCurrent.setText(formatTime(pos));
            tvTimeRemaining.setText(getString(R.string.time_remaining_fmt, formatTime(dur - pos)));
            updateSynchronizedLyrics(pos, dur);
        }
    }

    private String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
