package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class EqualizerActivity extends AppCompatActivity {

    private final SeekBar[] bandSeekBars = new SeekBar[5];
    private final TextView[] bandGainLabels = new TextView[5];

    private TextView tvEngineStatus, tvRouteName, tvRouteSub;
    private ImageView iconHeadphone;
    private ProgressBar circularBass, circularVirt;
    private TextView tvBassPct, tvVirtPct;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPlayPause, btnNext;
    private ShapeableImageView miniArt;
    private View miniPlayerContainer;

    private AudioDeviceCallback routeCallback;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

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
        setContentView(R.layout.activity_equalizer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_eq);
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_open_profile) {
                    startActivity(new Intent(this, ProfileSettingsActivity.class));
                    return true;
                }
                return false;
            });
        }

        tvEngineStatus = findViewById(R.id.tv_eq_engine_status);
        tvRouteName = findViewById(R.id.tv_eq_route_name);
        tvRouteSub = findViewById(R.id.tv_eq_route_sub);
        iconHeadphone = findViewById(R.id.icon_headphone);
        MaterialSwitch switchEqMaster = findViewById(R.id.switch_eq_master);

        circularBass = findViewById(R.id.circular_bass);
        circularVirt = findViewById(R.id.circular_virt);
        tvBassPct = findViewById(R.id.tv_bass_pct);
        tvVirtPct = findViewById(R.id.tv_virt_pct);

        int[] seekIds = {R.id.seek_band_0, R.id.seek_band_1, R.id.seek_band_2, R.id.seek_band_3, R.id.seek_band_4};
        int[] gainIds = {R.id.tv_band_gain_0, R.id.tv_band_gain_1, R.id.tv_band_gain_2, R.id.tv_band_gain_3, R.id.tv_band_gain_4};

        // PlaybackService listens to OpenRouterAiHelper.PREFS_NAME in real time and owns the ExoPlayer DSP session
        SharedPreferences prefs = getSharedPreferences(OpenRouterAiHelper.PREFS_NAME, MODE_PRIVATE);
        int[] defaultProgress = {70, 60, 50, 65, 75};

        for (int i = 0; i < 5; i++) {
            final int bandIdx = i;
            bandSeekBars[i] = findViewById(seekIds[i]);
            bandGainLabels[i] = findViewById(gainIds[i]);
            int savedProg = prefs.getInt("eq_band_" + i, defaultProgress[i]);
            if (bandSeekBars[i] != null) {
                bandSeekBars[i].setProgress(savedProg);
                updateBandLabel(bandIdx, savedProg);
                bandSeekBars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        updateBandLabel(bandIdx, progress);
                        if (fromUser) {
                            prefs.edit()
                                    .putInt("eq_band_" + bandIdx, progress)
                                    .putString("eq_preset_name", "CUSTOM")
                                    .apply();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        }

        boolean masterEnabled = prefs.getBoolean("eq_master_enabled", true);
        if (switchEqMaster != null) {
            switchEqMaster.setChecked(masterEnabled);
            updateMasterStatusLabel(masterEnabled);
            switchEqMaster.setOnCheckedChangeListener((b, isChecked) -> {
                prefs.edit().putBoolean("eq_master_enabled", isChecked).apply();
                updateMasterStatusLabel(isChecked);
            });
        }

        Chip chipFlat = findViewById(R.id.chip_eq_flat);
        Chip chipBass = findViewById(R.id.chip_eq_bass);
        Chip chipRock = findViewById(R.id.chip_eq_rock);
        Chip chipElec = findViewById(R.id.chip_eq_electronic);

        if (chipFlat != null) chipFlat.setOnClickListener(v -> applyPreset("FLAT", new int[]{50, 50, 50, 50, 50}));
        if (chipBass != null) chipBass.setOnClickListener(v -> applyPreset("BASS BOOST", new int[]{85, 72, 50, 52, 58}));
        if (chipRock != null) chipRock.setOnClickListener(v -> applyPreset("ROCK", new int[]{75, 62, 44, 68, 80}));
        if (chipElec != null) chipElec.setOnClickListener(v -> applyPreset("ELECTRONIC", new int[]{70, 60, 50, 65, 75}));

        View btnZeroReset = findViewById(R.id.btn_zero_reset);
        if (btnZeroReset != null) {
            btnZeroReset.setOnClickListener(v -> {
                applyPreset("BALANCED", new int[]{50, 50, 50, 50, 50});
                if (chipFlat != null) chipFlat.setChecked(true);
                Toast.makeText(this, "EQ bands reset to 0dB", Toast.LENGTH_SHORT).show();
            });
        }

        int bassPct = prefs.getInt("dsp_bass_pct", 65);
        int virtPct = prefs.getInt("dsp_virt_pct", 40);
        applyBassStrengthUi(bassPct);
        applyVirtStrengthUi(virtPct);

        View cardBass = findViewById(R.id.card_bass_boost);
        if (cardBass != null) {
            cardBass.setOnClickListener(v -> {
                int next = (prefs.getInt("dsp_bass_pct", 65) + 25) % 125;
                prefs.edit().putInt("dsp_bass_pct", next).apply();
                applyBassStrengthUi(next);
            });
            UiAnimationHelper.attachSpringTouch(cardBass);
        }

        View cardVirt = findViewById(R.id.card_virtualizer);
        if (cardVirt != null) {
            cardVirt.setOnClickListener(v -> {
                int next = (prefs.getInt("dsp_virt_pct", 40) + 25) % 125;
                prefs.edit().putInt("dsp_virt_pct", next).apply();
                applyVirtStrengthUi(next);
            });
            UiAnimationHelper.attachSpringTouch(cardVirt);
        }

        View cardRoute = findViewById(R.id.card_eq_audio_route);
        if (cardRoute != null) {
            cardRoute.setOnClickListener(v -> AudioRouteHelper.showAudioOutputSwitcher(this));
        }

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);
        miniPlayerContainer = findViewById(R.id.mini_player_eq);
        setupMiniPlayer();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_eq);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_eq);
    }

    private void applyPreset(String presetName, int[] values) {
        SharedPreferences.Editor editor = getSharedPreferences(OpenRouterAiHelper.PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString("eq_preset_name", presetName);
        for (int i = 0; i < Math.min(5, values.length); i++) {
            editor.putInt("eq_band_" + i, values[i]);
            if (bandSeekBars[i] != null) bandSeekBars[i].setProgress(values[i]);
            updateBandLabel(i, values[i]);
        }
        editor.apply();
    }

    private void updateBandLabel(int bandIdx, int progress) {
        int db = Math.round((progress - 50) / 5.0f);
        if (bandGainLabels[bandIdx] != null) {
            String label = db > 0 ? "+" + db + "dB" : db + "dB";
            bandGainLabels[bandIdx].setText(label);
            bandGainLabels[bandIdx].setTextColor(ContextCompat.getColor(
                    this, db == 0 ? R.color.text_secondary : R.color.accent_blue));
        }
    }

    private void applyBassStrengthUi(int pct) {
        if (circularBass != null) circularBass.setProgress(pct);
        if (tvBassPct != null) tvBassPct.setText(String.format(Locale.US, "%d%%", pct));
    }

    private void applyVirtStrengthUi(int pct) {
        if (circularVirt != null) circularVirt.setProgress(pct);
        if (tvVirtPct != null) tvVirtPct.setText(String.format(Locale.US, "%d%%", pct));
    }

    private void updateMasterStatusLabel(boolean enabled) {
        if (tvEngineStatus == null) return;
        tvEngineStatus.setText(enabled ? R.string.eq_engine_status_active : R.string.eq_engine_status_bypassed);
        tvEngineStatus.setTextColor(ContextCompat.getColor(this, enabled ? R.color.accent_blue : R.color.text_secondary));
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
        AudioRouteHelper.bindRouteViews(this, tvRouteName, tvRouteSub, iconHeadphone);
        routeCallback = AudioRouteHelper.registerLiveRouteCallback(this,
                () -> AudioRouteHelper.bindRouteViews(this, tvRouteName, tvRouteSub, iconHeadphone));

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
