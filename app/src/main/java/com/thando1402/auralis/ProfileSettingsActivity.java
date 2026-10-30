package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioDeviceCallback;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileSettingsActivity extends AppCompatActivity {

    private TextView textUserName, textUserSubtitle;
    private TextView tvStatTracks, tvStatPlaylists, tvStatLocalSize, tvStatSyncMode;
    private TextView textDacDevice, textDacCodec;
    private ImageView iconDacHeadphones;
    private MaterialSwitch switchBitPerfect;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPlayPause, btnNext;
    private ShapeableImageView miniArt;
    private View miniPlayerContainer;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private AudioDeviceCallback routeCallback;
    private ExecutorService statsExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            syncMiniPlayerUI();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
            syncMiniPlayerUI();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (btnPlayPause != null) {
                btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_settings);

        statsExecutor = Executors.newSingleThreadExecutor();

        textUserName = findViewById(R.id.text_user_name);
        textUserSubtitle = findViewById(R.id.text_user_subtitle);
        tvStatTracks = findViewById(R.id.tv_stat_tracks);
        tvStatPlaylists = findViewById(R.id.tv_stat_playlists);
        tvStatLocalSize = findViewById(R.id.tv_stat_local_size);
        tvStatSyncMode = findViewById(R.id.tv_stat_sync_mode);
        textDacDevice = findViewById(R.id.text_dac_device);
        textDacCodec = findViewById(R.id.text_dac_codec);
        iconDacHeadphones = findViewById(R.id.icon_dac_headphones);
        switchBitPerfect = findViewById(R.id.switch_bitperfect);

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);
        miniPlayerContainer = findViewById(R.id.mini_player_profile);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_profile);
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_openrouter_key) {
                    showOpenRouterApiKeyDialog();
                    return true;
                } else if (id == R.id.action_reset_settings) {
                    resetDspDefaults();
                    return true;
                } else if (id == R.id.action_about) {
                    showAboutDialog();
                    return true;
                }
                return false;
            });
        }

        View.OnClickListener openProfileSetup = v ->
                startActivity(new Intent(this, ProfileSetupActivity.class));
        View cardIdentity = findViewById(R.id.card_profile_identity);
        if (cardIdentity != null) cardIdentity.setOnClickListener(openProfileSetup);
        View btnOpenSetup = findViewById(R.id.btn_open_profile_setup);
        if (btnOpenSetup != null) {
            btnOpenSetup.setOnClickListener(openProfileSetup);
            UiAnimationHelper.attachSpringTouch(btnOpenSetup);
        }

        View btnConfigureAi = findViewById(R.id.btn_configure_openrouter);
        if (btnConfigureAi != null) {
            btnConfigureAi.setOnClickListener(v -> showOpenRouterApiKeyDialog());
            UiAnimationHelper.attachSpringTouch(btnConfigureAi);
        }

        View cardDac = findViewById(R.id.card_profile_dac);
        if (cardDac != null) {
            cardDac.setOnClickListener(v -> AudioRouteHelper.showAudioOutputSwitcher(this));
        }

        SharedPreferences prefs = getSharedPreferences(OpenRouterAiHelper.PREFS_NAME, MODE_PRIVATE);
        if (switchBitPerfect != null) {
            switchBitPerfect.setChecked(prefs.getBoolean("bit_perfect_dac", true));
            switchBitPerfect.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean("bit_perfect_dac", checked).apply();
                Toast.makeText(this, checked ? "Bit-Perfect Direct DAC Enabled" : "System Mixer Mode", Toast.LENGTH_SHORT).show();
            });
        }

        View btnDiagnostics = findViewById(R.id.btn_run_diagnostics);
        if (btnDiagnostics != null) {
            btnDiagnostics.setOnClickListener(v -> showDiagnosticsDialog());
            UiAnimationHelper.attachSpringTouch(btnDiagnostics);
        }

        setupMiniPlayer();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_profile);
        NavigationHelper.setupBottomNavigation(this, bottomNav, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshIdentityAndStats();
    }

    private void refreshIdentityAndStats() {
        if (textUserName != null) {
            textUserName.setText(OpenRouterAiHelper.getProfileName(this));
        }
        FirebaseUser user = null;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (Exception ignored) {
        }

        if (textUserSubtitle != null) {
            if (user != null) {
                String authInfo = user.isAnonymous()
                        ? "FIREBASE SPARK • ANONYMOUS SESSION"
                        : "FIREBASE SPARK • " + (user.getEmail() != null ? user.getEmail() : "SYNCED");
                textUserSubtitle.setText(authInfo);
            } else {
                textUserSubtitle.setText(R.string.profile_default_subtitle);
            }
        }
        if (tvStatSyncMode != null) {
            tvStatSyncMode.setText(user != null ? "CLOUD" : "100%");
        }

        statsExecutor.execute(() -> {
            int trackCount = 0;
            long totalBytes = 0L;
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = new String[]{
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE
            };
            try (Cursor c = getContentResolver().query(collection, proj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
                if (c != null) {
                    int tCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int dCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int sCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    while (c.moveToNext()) {
                        if (!AudioFilterManager.shouldExcludeTrack(getApplicationContext(),
                                c.getString(tCol), c.getString(dCol), c.getLong(durCol))) {
                            trackCount++;
                            totalBytes += Math.max(0L, c.getLong(sCol));
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            int playlistCount = 0;
            try {
                SharedPreferences plPrefs = getSharedPreferences(PlaylistsActivity.PREFS_PLAYLISTS, MODE_PRIVATE);
                JSONObject obj = new JSONObject(plPrefs.getString(PlaylistsActivity.KEY_JSON, "{}"));
                playlistCount = obj.length();
            } catch (Exception ignored) {
            }

            final int finalTracks = trackCount;
            final int finalPlaylists = playlistCount;
            final String formattedBytes = Formatter.formatShortFileSize(getApplicationContext(), totalBytes);

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (tvStatTracks != null) tvStatTracks.setText(String.valueOf(finalTracks));
                if (tvStatPlaylists != null) tvStatPlaylists.setText(String.valueOf(finalPlaylists));
                if (tvStatLocalSize != null) tvStatLocalSize.setText(formattedBytes);
            });
        });
    }

    private void showOpenRouterApiKeyDialog() {
        EditText keyInput = new EditText(this);
        keyInput.setHint("sk-or-v1-...");
        keyInput.setText(OpenRouterAiHelper.getApiKey(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_openrouter_key)
                .setView(keyInput)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    OpenRouterAiHelper.saveApiKey(this, keyInput.getText() != null ? keyInput.getText().toString() : "");
                    Toast.makeText(this, "OpenRouter AI key saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetDspDefaults() {
        SharedPreferences prefs = getSharedPreferences(OpenRouterAiHelper.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putBoolean("eq_master_enabled", true)
                .putString("eq_preset_name", "BALANCED")
                .putBoolean("bit_perfect_dac", true)
                .apply();
        if (switchBitPerfect != null) switchBitPerfect.setChecked(true);
        Toast.makeText(this, "DSP Engine reset to balanced defaults", Toast.LENGTH_SHORT).show();
    }

    private void showDiagnosticsDialog() {
        AudioRouteHelper.RouteInfo route = AudioRouteHelper.getActiveRouteInfo(this);
        int queuedCrashes = AuralisTelemetryManager.getQueuedCrashReportCount(this);
        if (queuedCrashes > 0) {
            AuralisTelemetryManager.scheduleCrashReportSync(this);
            AuralisTelemetryManager.flushPendingReportsAsync(this);
        }
        String report = "• Active Audio Output: " + route.deviceName + "\n"
                + "• Signal Path: " + route.codecSubtitle + "\n"
                + "• Active Media3 Session ID: " + PlaybackService.getActiveAudioSessionId() + "\n"
                + "• Heap Memory Used: " + AuralisTelemetryManager.getRamUsageMb() + " MB\n"
                + "• Offline Error Queue (JSON): " + queuedCrashes + " pending\n"
                + "• Synced Lyrics Folder:\n  " + SongMetadataManager.getSyncedLyricsDir(this).getAbsolutePath() + "\n"
                + "• Original Backup Folder:\n  " + SongMetadataManager.getBackupLyricsDir(this).getAbsolutePath();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Audio & Telemetry Diagnostics")
                .setMessage(report)
                .setNeutralButton("Sync Firebase Now", (d, w) -> {
                    AuralisTelemetryManager.flushPendingReportsAsync(this);
                    Toast.makeText(this, "Syncing queued telemetry to Firebase Crashlytics & Firestore...", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAboutDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_about_auralis)
                .setMessage("Auralis Hi-Res Offline Music Player\n"
                        + "• Media3 Foreground Playback & Audio Focus\n"
                        + "• Scoped Storage & MicroSD Support\n"
                        + "• ±200ms Fuzzy .LRC Sync + Dedicated Backup Folder\n"
                        + "• Store-and-Forward WorkManager Crash Telemetry")
                .setPositiveButton("Close", null)
                .show();
    }

    private void setupMiniPlayer() {
        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(v -> {
                if (mediaController != null) {
                    if (mediaController.isPlaying()) mediaController.pause();
                    else mediaController.play();
                }
            });
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToNext();
            });
        }
        if (miniPlayerContainer != null) {
            miniPlayerContainer.setOnClickListener(v -> {
                startActivity(new Intent(this, NowPlayingActivity.class));
                overridePendingTransition(R.anim.slide_up_in, 0);
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AudioRouteHelper.bindRouteViews(this, textDacDevice, textDacCodec, iconDacHeadphones);
        routeCallback = AudioRouteHelper.registerLiveRouteCallback(this,
                () -> AudioRouteHelper.bindRouteViews(this, textDacDevice, textDacCodec, iconDacHeadphones));

        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        final ListenableFuture<MediaController> future = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture = future;
        future.addListener(() -> {
            if (future != controllerFuture || future.isCancelled() || isFinishing() || isDestroyed()) return;
            try {
                mediaController = future.get();
                mediaController.addListener(playerListener);
                syncMiniPlayerUI();
            } catch (ExecutionException | InterruptedException | CancellationException ignored) {
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        AudioRouteHelper.unregisterLiveRouteCallback(this, routeCallback);
        routeCallback = null;
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
        if (statsExecutor != null) {
            statsExecutor.shutdownNow();
        }
    }

    private void syncMiniPlayerUI() {
        PlaybackStateHelper.syncMiniPlayerView(
                this,
                mediaController,
                miniPlayerContainer,
                miniTitle,
                miniSubtitle,
                miniArt,
                btnPlayPause
        );
    }
}
