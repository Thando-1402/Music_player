package com.thando1402.auralis;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

public class FolderExclusionsActivity extends AppCompatActivity {

    private MaterialSwitch switchIgnoreHidden;
    private MaterialSwitch switchRespectNomedia;
    private MaterialSwitch switchFilterShort;
    private MaterialSwitch switchExcludeWhatsapp;
    private MaterialSwitch switchExcludePodcasts;
    private MaterialSwitch switchExcludeHires;
    private TextView summaryExcludedCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_exclusions);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_exclusions);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        summaryExcludedCount = findViewById(R.id.summary_excluded_count);
        switchIgnoreHidden = findViewById(R.id.switch_ignore_hidden);
        switchRespectNomedia = findViewById(R.id.switch_respect_nomedia);
        switchFilterShort = findViewById(R.id.switch_filter_short);
        switchExcludeWhatsapp = findViewById(R.id.switch_exclude_whatsapp);
        switchExcludePodcasts = findViewById(R.id.switch_exclude_podcasts);
        switchExcludeHires = findViewById(R.id.switch_exclude_hires);

        syncSwitchesFromPrefs();

        if (switchIgnoreHidden != null) {
            switchIgnoreHidden.setOnCheckedChangeListener((b, isChecked) -> {
                AudioFilterManager.setIgnoreHiddenEnabled(this, isChecked);
                updateSummaryText();
            });
        }
        if (switchRespectNomedia != null) {
            switchRespectNomedia.setOnCheckedChangeListener((b, isChecked) -> {
                AudioFilterManager.setRespectNomediaEnabled(this, isChecked);
                updateSummaryText();
            });
        }
        if (switchFilterShort != null) {
            switchFilterShort.setOnCheckedChangeListener((b, isChecked) -> {
                AudioFilterManager.setFilterShortClipsEnabled(this, isChecked);
                updateSummaryText();
            });
        }
        if (switchExcludeWhatsapp != null) {
            switchExcludeWhatsapp.setOnCheckedChangeListener((b, isChecked) -> {
                AudioFilterManager.setHideWhatsAppEnabled(this, isChecked);
                updateSummaryText();
            });
        }
        if (switchExcludePodcasts != null) {
            switchExcludePodcasts.setOnCheckedChangeListener((b, isChecked) -> {
                AudioFilterManager.setExcludePodcastsEnabled(this, isChecked);
                updateSummaryText();
            });
        }

        findViewById(R.id.btn_exclude_all).setOnClickListener(v -> {
            AudioFilterManager.setIgnoreHiddenEnabled(this, true);
            AudioFilterManager.setRespectNomediaEnabled(this, true);
            AudioFilterManager.setFilterShortClipsEnabled(this, true);
            AudioFilterManager.setHideWhatsAppEnabled(this, true);
            AudioFilterManager.setExcludePodcastsEnabled(this, true);
            syncSwitchesFromPrefs();
        });

        findViewById(R.id.btn_reset_all).setOnClickListener(v -> {
            AudioFilterManager.clearExcludedFolders(this);
            updateSummaryText();
            Toast.makeText(this, "Custom hidden folders restored", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_restore_defaults).setOnClickListener(v -> {
            AudioFilterManager.clearExcludedFolders(this);
            AudioFilterManager.setIgnoreHiddenEnabled(this, true);
            AudioFilterManager.setRespectNomediaEnabled(this, true);
            AudioFilterManager.setFilterShortClipsEnabled(this, true);
            AudioFilterManager.setHideWhatsAppEnabled(this, true);
            AudioFilterManager.setExcludePodcastsEnabled(this, false);
            syncSwitchesFromPrefs();
            Toast.makeText(this, "Filter defaults restored", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_apply_and_rescan).setOnClickListener(v -> {
            Toast.makeText(this, "Filters applied", Toast.LENGTH_SHORT).show();
            finish();
        });
        findViewById(R.id.btn_action_apply_top).setOnClickListener(v -> finish());
        findViewById(R.id.btn_action_refresh).setOnClickListener(v -> {
            syncSwitchesFromPrefs();
            Toast.makeText(this, "Filter status refreshed", Toast.LENGTH_SHORT).show();
        });
    }

    private void syncSwitchesFromPrefs() {
        if (switchIgnoreHidden != null) switchIgnoreHidden.setChecked(AudioFilterManager.isIgnoreHiddenEnabled(this));
        if (switchRespectNomedia != null) switchRespectNomedia.setChecked(AudioFilterManager.isRespectNomediaEnabled(this));
        if (switchFilterShort != null) switchFilterShort.setChecked(AudioFilterManager.isFilterShortClipsEnabled(this));
        if (switchExcludeWhatsapp != null) switchExcludeWhatsapp.setChecked(AudioFilterManager.isHideWhatsAppEnabled(this));
        if (switchExcludePodcasts != null) switchExcludePodcasts.setChecked(AudioFilterManager.isExcludePodcastsEnabled(this));
        if (switchExcludeHires != null) switchExcludeHires.setChecked(false);
        updateSummaryText();
    }

    private void updateSummaryText() {
        if (summaryExcludedCount == null) return;
        int activeRules = 0;
        if (AudioFilterManager.isIgnoreHiddenEnabled(this)) activeRules++;
        if (AudioFilterManager.isRespectNomediaEnabled(this)) activeRules++;
        if (AudioFilterManager.isFilterShortClipsEnabled(this)) activeRules++;
        if (AudioFilterManager.isHideWhatsAppEnabled(this)) activeRules++;
        if (AudioFilterManager.isExcludePodcastsEnabled(this)) activeRules++;
        int customFolders = AudioFilterManager.getExcludedFolders(this).size();
        summaryExcludedCount.setText("🛡 " + activeRules + " Active Filter Rules • " + customFolders + " Custom Hidden Folders");
    }
}