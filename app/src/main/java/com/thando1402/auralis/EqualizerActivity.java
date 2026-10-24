package com.thando1402.auralis;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Bundle;
import android.view.View;
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
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class EqualizerActivity extends AppCompatActivity {

    private static final String PREFS_EQ = "auralis_eq_prefs";

    private final SeekBar[] bandSeekBars = new SeekBar[5];
    private final TextView[] bandValTexts = new TextView[5];
    private SeekBar seekBass, seekVirtualizer;
    private TextView valBass, valVirtualizer, textEqStatus;
    private MaterialSwitch switchMaster;

    private Equalizer hardwareEq;
    private BassBoost hardwareBass;
    private Virtualizer hardwareVirtualizer;
    private short minBandMb = -1500;
    private short maxBandMb = 1500;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPrev, btnPlayPause, btnNext;
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
            if (btnPlayPause != null) {
                btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);

        switchMaster = findViewById(R.id.switch_eq_master);
        textEqStatus = findViewById(R.id.text_eq_status);

        int[] seekIds = {R.id.seek_band_0, R.id.seek_band_1, R.id.seek_band_2, R.id.seek_band_3, R.id.seek_band_4};
        int[] valIds = {R.id.val_band_0, R.id.val_band_1, R.id.val_band_2, R.id.val_band_3, R.id.val_band_4};

        SharedPreferences prefs = getSharedPreferences(PREFS_EQ, MODE_PRIVATE);
        boolean eqEnabled = prefs.getBoolean("eq_enabled", true);
        if (switchMaster != null) switchMaster.setChecked(eqEnabled);

        initHardwareDsp(eqEnabled);

        for (int i = 0; i < 5; i++) {
            final int bandIndex = i;
            bandSeekBars[i] = findViewById(seekIds[i]);
            bandValTexts[i] = findViewById(valIds[i]);

            int savedProgress = prefs.getInt("band_" + i, 1500);
            if (bandSeekBars[i] != null) {
                bandSeekBars[i].setMax(maxBandMb - minBandMb);
                bandSeekBars[i].setProgress(savedProgress);
                applyBandProgress(bandIndex, savedProgress);

                bandSeekBars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        applyBandProgress(bandIndex, progress);
                        if (fromUser) {
                            prefs.edit().putInt("band_" + bandIndex, progress).apply();
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        }

        seekBass = findViewById(R.id.seek_bass_boost);
        valBass = findViewById(R.id.val_bass_boost);
        seekVirtualizer = findViewById(R.id.seek_virtualizer);
        valVirtualizer = findViewById(R.id.val_virtualizer);

        if (seekBass != null) {
            int bassProg = prefs.getInt("bass_boost", 250);
            seekBass.setProgress(bassProg);
            if (valBass != null) valBass.setText((bassProg / 10) + "%");
            seekBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (valBass != null) valBass.setText((progress / 10) + "%");
                    try {
                        if (hardwareBass != null) hardwareBass.setStrength((short) progress);
                    } catch (Exception ignored) {}
                    if (fromUser) prefs.edit().putInt("bass_boost", progress).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (seekVirtualizer != null) {
            int virtProg = prefs.getInt("virtualizer", 150);
            seekVirtualizer.setProgress(virtProg);
            if (valVirtualizer != null) valVirtualizer.setText((virtProg / 10) + "%");
            seekVirtualizer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (valVirtualizer != null) valVirtualizer.setText((progress / 10) + "%");
                    try {
                        if (hardwareVirtualizer != null) hardwareVirtualizer.setStrength((short) progress);
                    } catch (Exception ignored) {}
                    if (fromUser) prefs.edit().putInt("virtualizer", progress).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (switchMaster != null) {
            switchMaster.setOnCheckedChangeListener((b, isChecked) -> {
                prefs.edit().putBoolean("eq_enabled", isChecked).apply();
                try {
                    if (hardwareEq != null) hardwareEq.setEnabled(isChecked);
                    if (hardwareBass != null) hardwareBass.setEnabled(isChecked);
                    if (hardwareVirtualizer != null) hardwareVirtualizer.setEnabled(isChecked);
                } catch (Exception ignored) {}
                if (textEqStatus != null) {
                    textEqStatus.setText(isChecked ? "5-BAND HARDWARE DSP • ACTIVE" : "5-BAND HARDWARE DSP • BYPASSED");
                }
            });
        }

        setupPresets(prefs);

        View btnAiEq = findViewById(R.id.btn_ai_auto_eq);
        if (btnAiEq != null) {
            btnAiEq.setOnClickListener(v -> applyAiSmartEq(prefs));
        }

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPrev = findViewById(R.id.mini_prev);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);
        setupMiniPlayer();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_eq);
        if (bottomNav != null) {
            NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_eq);
        }
    }

    private void initHardwareDsp(boolean enabled) {
        int sessionId = PlaybackService.getActiveAudioSessionId();
        try {
            hardwareEq = new Equalizer(0, sessionId);
            hardwareEq.setEnabled(enabled);
            short[] range = hardwareEq.getBandLevelRange();
            if (range != null && range.length == 2) {
                minBandMb = range[0];
                maxBandMb = range[1];
            }
            hardwareBass = new BassBoost(0, sessionId);
            hardwareBass.setEnabled(enabled);
            hardwareVirtualizer = new Virtualizer(0, sessionId);
            hardwareVirtualizer.setEnabled(enabled);
        } catch (Exception ignored) {
        }
    }

    private void applyBandProgress(int bandIndex, int progress) {
        int millibels = minBandMb + progress;
        float db = millibels / 100.0f;
        if (bandValTexts[bandIndex] != null) {
            bandValTexts[bandIndex].setText(String.format(Locale.US, "%+.1f dB", db));
        }
        try {
            if (hardwareEq != null && bandIndex < hardwareEq.getNumberOfBands()) {
                hardwareEq.setBandLevel((short) bandIndex, (short) millibels);
            }
        } catch (Exception ignored) {
        }
    }

    private void setupPresets(SharedPreferences prefs) {
        int center = -minBandMb;
        Chip flat = findViewById(R.id.chip_preset_flat);
        Chip bass = findViewById(R.id.chip_preset_bass);
        Chip vocal = findViewById(R.id.chip_preset_vocal);
        Chip harman = findViewById(R.id.chip_preset_harman);

        if (flat != null) flat.setOnClickListener(v -> setAllBands(prefs, new int[]{center, center, center, center, center}, "Flat Reference"));
        if (bass != null) bass.setOnClickListener(v -> setAllBands(prefs, new int[]{center + 650, center + 450, center, center + 150, center + 300}, "Bass & Sub Boost"));
        if (vocal != null) vocal.setOnClickListener(v -> setAllBands(prefs, new int[]{center - 200, center + 100, center + 550, center + 450, center + 200}, "Vocal Clarity"));
        if (harman != null) harman.setOnClickListener(v -> setAllBands(prefs, new int[]{center + 450, center + 150, center - 100, center + 350, center + 500}, "Harman Target Curve"));
    }

    private void setAllBands(SharedPreferences prefs, int[] values, String label) {
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < 5; i++) {
            int clamped = Math.max(0, Math.min(maxBandMb - minBandMb, values[i]));
            if (bandSeekBars[i] != null) bandSeekBars[i].setProgress(clamped);
            applyBandProgress(i, clamped);
            ed.putInt("band_" + i, clamped);
        }
        ed.apply();
        if (textEqStatus != null) textEqStatus.setText("PRESET ACTIVE • " + label.toUpperCase(Locale.US));
    }

    private void applyAiSmartEq(SharedPreferences prefs) {
        String currentTitle = miniTitle != null ? miniTitle.getText().toString() : "";
        int center = -minBandMb;
        if (currentTitle.toLowerCase(Locale.US).contains("acoustic") || currentTitle.toLowerCase(Locale.US).contains("vocal")) {
            setAllBands(prefs, new int[]{center - 100, center + 200, center + 500, center + 400, center + 250}, "AI Acoustic / Vocal Match");
        } else {
            setAllBands(prefs, new int[]{center + 500, center + 250, center - 50, center + 300, center + 450}, "AI Dynamic Mastering Curve");
        }
        Toast.makeText(this, "✨ AI tuned 5-band DSP for: " + currentTitle, Toast.LENGTH_SHORT).show();
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
        if (btnPrev != null) {
            btnPrev.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToPrevious();
            });
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToNext();
            });
        }
        View miniContainer = findViewById(R.id.mini_player_eq);
        if (miniContainer != null) {
            miniContainer.setOnClickListener(v -> {
                startActivity(new Intent(this, NowPlayingActivity.class));
                overridePendingTransition(R.anim.slide_up_in, 0);
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        final ListenableFuture<MediaController> future = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture = future;
        future.addListener(() -> {
            if (future != controllerFuture || future.isCancelled() || isFinishing() || isDestroyed()) {
                return;
            }
            try {
                mediaController = future.get();
                mediaController.addListener(playerListener);
                syncMiniPlayerUI();
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
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
        try {
            if (hardwareEq != null) hardwareEq.release();
            if (hardwareBass != null) hardwareBass.release();
            if (hardwareVirtualizer != null) hardwareVirtualizer.release();
        } catch (Exception ignored) {}
    }

    private void syncMiniPlayerUI() {
        if (mediaController == null) return;
        MediaItem currentItem = mediaController.getCurrentMediaItem();
        if (currentItem != null && currentItem.mediaMetadata != null) {
            CharSequence title = currentItem.mediaMetadata.title;
            CharSequence artist = currentItem.mediaMetadata.artist;
            if (miniTitle != null && title != null && title.length() > 0) miniTitle.setText(title);
            if (miniSubtitle != null && artist != null && artist.length() > 0) miniSubtitle.setText(artist);
            if (miniArt != null) {
                if (currentItem.mediaMetadata.artworkUri != null) {
                    miniArt.setImageURI(currentItem.mediaMetadata.artworkUri);
                } else {
                    miniArt.setImageResource(R.drawable.ic_album_placeholder);
                }
            }
        }
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(mediaController.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
        }
    }
}