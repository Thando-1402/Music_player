package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class EqualizerActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "auralis_eq_settings";
    private static final String KEY_EQ_ENABLED = "eq_enabled";
    private static final String KEY_BASS_LEVEL = "bass_level";
    private static final String KEY_VIRT_LEVEL = "virt_level";
    private static final String KEY_BAND_PREFIX = "band_";

    private MaterialSwitch switchEqMaster;
    private TextView textEngineStatus;

    private SeekBar[] seekBars = new SeekBar[5];
    private TextView[] textDbs = new TextView[5];
    private final int[] bandLevels = new int[]{70, 60, 50, 65, 75}; // Default Electronic profile

    private ProgressBar circularBass, circularVirt;
    private TextView textBassPercent, textVirtPercent;
    private int bassPercent = 65;
    private int virtPercent = 40;

    private Chip chipFlat, chipBass, chipRock, chipElectronic;

    // Audio effects
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;

    // Mini Player
    private View miniPlayerContainer;
    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnMiniPrev, btnMiniPlayPause, btnMiniNext;
    private ShapeableImageView miniArt;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            syncMiniPlayerUI();
        }

        @Override
        public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
            syncMiniPlayerUI();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (btnMiniPlayPause != null) {
                btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_eq);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_eq);

        initViews();
        loadSavedSettings();
        setupPresets();
        setupBassAndVirtualizer();
        setupMiniPlayer();
    }

    private void initViews() {
        switchEqMaster = findViewById(R.id.switch_eq_master);
        textEngineStatus = findViewById(R.id.text_eq_engine_status);

        seekBars[0] = findViewById(R.id.seekbar_band1);
        seekBars[1] = findViewById(R.id.seekbar_band2);
        seekBars[2] = findViewById(R.id.seekbar_band3);
        seekBars[3] = findViewById(R.id.seekbar_band4);
        seekBars[4] = findViewById(R.id.seekbar_band5);

        textDbs[0] = findViewById(R.id.text_db_band1);
        textDbs[1] = findViewById(R.id.text_db_band2);
        textDbs[2] = findViewById(R.id.text_db_band3);
        textDbs[3] = findViewById(R.id.text_db_band4);
        textDbs[4] = findViewById(R.id.text_db_band5);

        circularBass = findViewById(R.id.circular_bass);
        circularVirt = findViewById(R.id.circular_virt);
        textBassPercent = findViewById(R.id.text_bass_percent);
        textVirtPercent = findViewById(R.id.text_virt_percent);

        chipFlat = findViewById(R.id.chip_preset_flat);
        chipBass = findViewById(R.id.chip_preset_bass);
        chipRock = findViewById(R.id.chip_preset_rock);
        chipElectronic = findViewById(R.id.chip_preset_electronic);

        // Master switch toggle
        if (switchEqMaster != null) {
            switchEqMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateEngineStatus(isChecked);
                setSlidersEnabled(isChecked);
                saveSettings();
            });
        }

        // Zero reset button
        View btnZeroReset = findViewById(R.id.btn_zero_reset);
        if (btnZeroReset != null) {
            btnZeroReset.setOnClickListener(v -> resetToZero());
        }

        // Setup SeekBars listeners
        for (int i = 0; i < 5; i++) {
            final int index = i;
            if (seekBars[index] != null) {
                seekBars[index].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        bandLevels[index] = progress;
                        updateDbLabel(index, progress);
                        if (fromUser) {
                            applyHardwareEqBand(index, progress);
                            saveSettings();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        }
    }

    private void updateDbLabel(int index, int progress) {
        if (textDbs[index] == null) return;
        // Progress 0..100 maps to -10dB..+10dB (50 is 0dB)
        int dbVal = (progress - 50) / 5;
        if (dbVal > 0) {
            textDbs[index].setText("+" + dbVal + "dB");
            textDbs[index].setTextColor(ContextCompat.getColor(this, R.color.accent_blue));
        } else if (dbVal == 0) {
            textDbs[index].setText("0dB");
            textDbs[index].setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else {
            textDbs[index].setText(dbVal + "dB");
            textDbs[index].setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private void updateEngineStatus(boolean enabled) {
        if (textEngineStatus != null) {
            textEngineStatus.setText(enabled ? "● Engine Active • 32-bit Float Processing" : "○ Engine Bypassed");
            textEngineStatus.setTextColor(ContextCompat.getColor(this, enabled ? R.color.accent_blue : R.color.text_secondary));
        }
        if (equalizer != null) {
            try {
                equalizer.setEnabled(enabled);
            } catch (Exception ignored) {}
        }
    }

    private void setSlidersEnabled(boolean enabled) {
        for (int i = 0; i < 5; i++) {
            if (seekBars[i] != null) seekBars[i].setEnabled(enabled);
        }
        if (circularBass != null) circularBass.setEnabled(enabled);
        if (circularVirt != null) circularVirt.setEnabled(enabled);
    }

    private void setupPresets() {
        if (chipFlat != null) {
            chipFlat.setOnClickListener(v -> applyPreset(50, 50, 50, 50, 50, 0, 0));
        }
        if (chipBass != null) {
            chipBass.setOnClickListener(v -> applyPreset(75, 65, 50, 55, 60, 85, 30));
        }
        if (chipRock != null) {
            chipRock.setOnClickListener(v -> applyPreset(70, 60, 45, 65, 75, 60, 50));
        }
        if (chipElectronic != null) {
            chipElectronic.setOnClickListener(v -> applyPreset(70, 60, 50, 65, 75, 65, 40));
        }
    }

    private void applyPreset(int b1, int b2, int b3, int b4, int b5, int bass, int virt) {
        bandLevels[0] = b1;
        bandLevels[1] = b2;
        bandLevels[2] = b3;
        bandLevels[3] = b4;
        bandLevels[4] = b5;

        for (int i = 0; i < 5; i++) {
            if (seekBars[i] != null) {
                seekBars[i].setProgress(bandLevels[i]);
                updateDbLabel(i, bandLevels[i]);
            }
            applyHardwareEqBand(i, bandLevels[i]);
        }

        updateBass(bass);
        updateVirt(virt);
        saveSettings();
    }

    private void resetToZero() {
        applyPreset(50, 50, 50, 50, 50, 0, 0);
        Toast.makeText(this, "EQ zeroed (Flat 0dB)", Toast.LENGTH_SHORT).show();
    }

    private void setupBassAndVirtualizer() {
        View cardBass = findViewById(R.id.card_bass_boost);
        if (cardBass != null) {
            cardBass.setOnClickListener(v -> {
                int next = (bassPercent + 20);
                if (next > 100) next = 0;
                updateBass(next);
                saveSettings();
            });
        }

        View cardVirt = findViewById(R.id.card_virtualizer);
        if (cardVirt != null) {
            cardVirt.setOnClickListener(v -> {
                int next = (virtPercent + 20);
                if (next > 100) next = 0;
                updateVirt(next);
                saveSettings();
            });
        }
    }

    private void updateBass(int percent) {
        bassPercent = percent;
        if (circularBass != null) circularBass.setProgress(percent);
        if (textBassPercent != null) textBassPercent.setText(percent + "%");
        if (bassBoost != null) {
            try {
                bassBoost.setEnabled(percent > 0);
                bassBoost.setStrength((short) ((percent * 1000) / 100));
            } catch (Exception ignored) {}
        }
    }

    private void updateVirt(int percent) {
        virtPercent = percent;
        if (circularVirt != null) circularVirt.setProgress(percent);
        if (textVirtPercent != null) textVirtPercent.setText(percent + "%");
        if (virtualizer != null) {
            try {
                virtualizer.setEnabled(percent > 0);
                virtualizer.setStrength((short) ((percent * 1000) / 100));
            } catch (Exception ignored) {}
        }
    }

    private void applyHardwareEqBand(int bandIndex, int progress) {
        if (equalizer == null) return;
        try {
            short[] range = equalizer.getBandLevelRange(); // min and max in millibels
            short min = range[0];
            short max = range[1];
            short level = (short) (min + (progress * (max - min)) / 100);
            if (bandIndex < equalizer.getNumberOfBands()) {
                equalizer.setBandLevel((short) bandIndex, level);
            }
        } catch (Exception ignored) {}
    }

    private void loadSavedSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isEnabled = prefs.getBoolean(KEY_EQ_ENABLED, true);
        if (switchEqMaster != null) switchEqMaster.setChecked(isEnabled);
        updateEngineStatus(isEnabled);

        for (int i = 0; i < 5; i++) {
            bandLevels[i] = prefs.getInt(KEY_BAND_PREFIX + i, bandLevels[i]);
            if (seekBars[i] != null) {
                seekBars[i].setProgress(bandLevels[i]);
                updateDbLabel(i, bandLevels[i]);
            }
        }

        bassPercent = prefs.getInt(KEY_BASS_LEVEL, 65);
        virtPercent = prefs.getInt(KEY_VIRT_LEVEL, 40);
        updateBass(bassPercent);
        updateVirt(virtPercent);
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (switchEqMaster != null) {
            editor.putBoolean(KEY_EQ_ENABLED, switchEqMaster.isChecked());
        }
        for (int i = 0; i < 5; i++) {
            editor.putInt(KEY_BAND_PREFIX + i, bandLevels[i]);
        }
        editor.putInt(KEY_BASS_LEVEL, bassPercent);
        editor.putInt(KEY_VIRT_LEVEL, virtPercent);
        editor.apply();
    }

    private void setupMiniPlayer() {
        miniPlayerContainer = findViewById(R.id.mini_player_eq);
        if (miniPlayerContainer == null) return;

        miniTitle = miniPlayerContainer.findViewById(R.id.mini_title);
        miniSubtitle = miniPlayerContainer.findViewById(R.id.mini_subtitle);
        btnMiniPrev = miniPlayerContainer.findViewById(R.id.mini_prev);
        btnMiniPlayPause = miniPlayerContainer.findViewById(R.id.mini_play_pause);
        btnMiniNext = miniPlayerContainer.findViewById(R.id.mini_next);
        miniArt = miniPlayerContainer.findViewById(R.id.mini_track_art);

        miniPlayerContainer.setOnClickListener(v -> {
            startActivity(new Intent(this, NowPlayingActivity.class));
            overridePendingTransition(R.anim.slide_up_in, 0);
        });

        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setOnClickListener(v -> {
                if (mediaController != null) {
                    if (mediaController.isPlaying()) mediaController.pause();
                    else mediaController.play();
                }
            });
        }

        if (btnMiniPrev != null) {
            btnMiniPrev.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToPrevious();
            });
        }

        if (btnMiniNext != null) {
            btnMiniNext.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToNext();
            });
        }
    }

    private void syncMiniPlayerUI() {
        if (miniPlayerContainer == null) return;
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) {
            miniPlayerContainer.setVisibility(View.GONE);
            return;
        }

        miniPlayerContainer.setVisibility(View.VISIBLE);
        MediaMetadata metadata = mediaController.getMediaMetadata();

        CharSequence title = metadata.title;
        CharSequence artist = metadata.artist;

        if (miniTitle != null) {
            miniTitle.setText(title != null && title.length() > 0 ? title : "Auralis Audio");
        }
        if (miniSubtitle != null) {
            miniSubtitle.setText(artist != null && artist.length() > 0 ? artist : "Equalizer & DSP");
        }
        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setImageResource(mediaController.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                if (!controllerFuture.isCancelled()) {
                    mediaController = controllerFuture.get();
                    if (mediaController != null) {
                        mediaController.addListener(playerListener);
                        syncMiniPlayerUI();
                    }
                }
            } catch (ExecutionException | InterruptedException | CancellationException ignored) {
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        if (mediaController != null) {
            mediaController.removeListener(playerListener);
            mediaController = null;
        }
        super.onStop();
    }
}