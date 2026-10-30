package com.thando1402.auralis;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditTrackActivity extends AppCompatActivity {

    private Uri songUri;
    private String songTitle;
    private String songArtist;

    private EditText editTitle, editArtist, editAlbum, editNum, editYearGenre;
    private TextView snippetLrc;
    private ShapeableImageView coverPreview;

    private Uri pendingCoverUri = null;
    private ExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> pickCoverLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingCoverUri = uri;
                    if (coverPreview != null) coverPreview.setImageURI(uri);
                }
            });

    private final ActivityResultLauncher<String> pickLrcLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    try (InputStream is = getContentResolver().openInputStream(uri);
                         BufferedReader br = is != null ? new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)) : null) {
                        if (br == null) return;
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append("\n");
                        if (snippetLrc != null) snippetLrc.setText(sb.toString().trim());
                        Toast.makeText(this, "Lyrics loaded from .lrc file", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to read .lrc file", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_track);
        ioExecutor = Executors.newSingleThreadExecutor();

        MaterialToolbar toolbar = findViewById(R.id.toolbar_edit_track);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        if (getIntent() != null) {
            String uriStr = getIntent().getStringExtra("EXTRA_SONG_URI");
            if (uriStr != null) songUri = Uri.parse(uriStr);
            songTitle = getIntent().getStringExtra("EXTRA_SONG_TITLE");
            songArtist = getIntent().getStringExtra("EXTRA_SONG_ARTIST");
        }

        editTitle = findViewById(R.id.input_track_title);
        editArtist = findViewById(R.id.input_track_artist);
        editAlbum = findViewById(R.id.input_track_album);
        editNum = findViewById(R.id.input_track_num);
        editYearGenre = findViewById(R.id.input_track_year_genre);
        snippetLrc = findViewById(R.id.snippet_lrc);
        TextView textFileSpecs = findViewById(R.id.text_track_file_specs);
        coverPreview = findViewById(R.id.edit_track_artwork);

        if (songUri != null) {
            Song temp = new Song(
                    songTitle != null ? songTitle : "",
                    songArtist != null ? songArtist : "",
                    songUri,
                    0);
            SongMetadataManager.applySavedEdits(this, temp, true);
            if (editTitle != null) editTitle.setText(temp.getTitle());
            if (editArtist != null) editArtist.setText(temp.getArtist());
            if (editAlbum != null && !temp.getAlbum().isEmpty()) editAlbum.setText(temp.getAlbum());
            if (editNum != null && !temp.getTrackNumber().isEmpty()) editNum.setText(temp.getTrackNumber());
            if (editYearGenre != null && !temp.getYearGenre().isEmpty()) editYearGenre.setText(temp.getYearGenre());

            if (temp.getCustomCoverUri() != null && coverPreview != null) {
                coverPreview.setImageURI(temp.getCustomCoverUri());
            } else if (coverPreview != null) {
                final Uri targetUri = songUri;
                ioExecutor.execute(() -> {
                    Bitmap existingArt = ArtworkColorHelper.loadArtworkBitmap(getApplicationContext(), null, targetUri, 360);
                    if (existingArt != null) {
                        mainHandler.post(() -> {
                            if (!isFinishing() && !isDestroyed() && pendingCoverUri == null) {
                                coverPreview.setImageBitmap(existingArt);
                            }
                        });
                    }
                });
            }

            if (snippetLrc != null && temp.getLyrics() != null && !temp.getLyrics().isEmpty()) {
                snippetLrc.setText(temp.getLyrics());
            }
            if (textFileSpecs != null) {
                File syncedDir = SongMetadataManager.getSyncedLyricsDir(this);
                File origDir = SongMetadataManager.getBackupLyricsDir(this);
                textFileSpecs.setText(getString(R.string.edit_track_storage_fmt, syncedDir.getName(), origDir.getName()));
            }
        }

        findViewById(R.id.btn_overlay_change_art).setOnClickListener(v -> pickCoverLauncher.launch("image/*"));
        findViewById(R.id.btn_gallery_art).setOnClickListener(v -> pickCoverLauncher.launch("image/*"));

        findViewById(R.id.btn_extract_art).setOnClickListener(v -> {
            if (songUri == null) return;
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(this, songUri);
                byte[] art = retriever.getEmbeddedPicture();
                if (art != null) {
                    File cacheFile = new File(getCacheDir(), "embedded_art_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                        fos.write(art);
                    }
                    pendingCoverUri = Uri.fromFile(cacheFile);
                    if (coverPreview != null) coverPreview.setImageURI(pendingCoverUri);
                    Toast.makeText(this, "Embedded cover extracted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No embedded cover found in this audio file", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Could not extract cover art", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_delete_art).setOnClickListener(v -> {
            pendingCoverUri = null;
            if (coverPreview != null) coverPreview.setImageResource(R.drawable.ic_album_placeholder_large);
            if (songUri != null) {
                SongMetadataManager.removeCustomCover(this, songUri.toString());
                Toast.makeText(this, "Custom cover removed", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_edit_lyrics).setOnClickListener(v -> {
            CharSequence[] options = new CharSequence[]{
                    "Type / Paste .lrc Lyrics",
                    "Import .lrc File from Storage",
                    "✨ AI Auto-Sync & Format Timestamps",
                    "Restore Original Backup Lyrics"
            };
            new AlertDialog.Builder(this)
                    .setTitle("Lyrics & Sync Options")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            EditText input = new EditText(this);
                            input.setHint("[00:12.00] Paste synced .lrc or plain lyrics");
                            String currentSnippet = getCleanSnippetLyrics();
                            if (!currentSnippet.isEmpty()) {
                                input.setText(currentSnippet);
                            }
                            new AlertDialog.Builder(this)
                                    .setTitle("Edit Synced Lyrics")
                                    .setView(input)
                                    .setPositiveButton("Apply", (d, w) -> {
                                        if (snippetLrc != null) snippetLrc.setText(input.getText().toString());
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else if (which == 1) {
                            pickLrcLauncher.launch("*/*");
                        } else if (which == 2) {
                            runAiLyricsSyncInEditor();
                        } else if (which == 3) {
                            if (songUri == null) return;
                            String t = editTitle != null ? editTitle.getText().toString().trim() : songTitle;
                            String a = editArtist != null ? editArtist.getText().toString().trim() : songArtist;
                            String restored = SongMetadataManager.restoreOriginalBackupLyrics(this, songUri.toString(), t, a);
                            if (restored != null && !restored.isEmpty()) {
                                if (snippetLrc != null) snippetLrc.setText(restored);
                                Toast.makeText(this, "Original backup lyrics restored!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "No original backup found yet", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .show();
        });

        findViewById(R.id.btn_save_track_header).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.btn_save_track_bottom).setOnClickListener(v -> saveAndFinish());

        View btnAiClean = findViewById(R.id.btn_ai_clean_tags);
        if (btnAiClean != null) {
            btnAiClean.setOnClickListener(v -> {
                String rawT = editTitle != null ? editTitle.getText().toString() : "";
                String rawA = editArtist != null ? editArtist.getText().toString() : "";
                Toast.makeText(this, "Cleaning tags...", Toast.LENGTH_SHORT).show();
                OpenRouterAiHelper.autoCleanSongMetadata(this, rawT, rawA, (cleanTitle, cleanArtist, usedCloudAi) -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (editTitle != null) editTitle.setText(cleanTitle);
                    if (editArtist != null) editArtist.setText(cleanArtist);
                    Toast.makeText(this, usedCloudAi ? "Cleaned via Cloud AI!" : "Cleaned via Offline Smart Parser!", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private String getCleanSnippetLyrics() {
        String rawSnippet = snippetLrc != null ? snippetLrc.getText().toString() : "";
        return rawSnippet.startsWith("No lyrics") ? "" : rawSnippet.trim();
    }

    private void runAiLyricsSyncInEditor() {
        String title = editTitle != null ? editTitle.getText().toString().trim() : "";
        String artist = editArtist != null ? editArtist.getText().toString().trim() : "";
        String currentLyrics = getCleanSnippetLyrics();

        Toast.makeText(this, "✨ Syncing & formatting timestamps...", Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            long durMs = 180_000L;
            if (songUri != null) {
                try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                    mmr.setDataSource(this, songUri);
                    String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durStr != null) durMs = Long.parseLong(durStr);
                } catch (Exception ignored) {
                }
            }
            final long resolvedDuration = durMs;
            mainHandler.post(() -> OpenRouterAiHelper.autoSyncAndFormatLyrics(
                    this,
                    title,
                    artist,
                    resolvedDuration,
                    currentLyrics,
                    (syncedLrc, usedCloudAi) -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (syncedLrc != null && !syncedLrc.isEmpty() && snippetLrc != null) {
                            snippetLrc.setText(syncedLrc);
                            Toast.makeText(this,
                                    usedCloudAi ? "✨ AI synced [mm:ss.xx] timestamps ready!" : "✨ Offline universal LRC formatted!",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Paste or import lyrics first to generate offline timestamps", Toast.LENGTH_SHORT).show();
                        }
                    }));
        });
    }

    private void saveAndFinish() {
        if (songUri != null) {
            String t = editTitle != null ? editTitle.getText().toString().trim() : songTitle;
            String a = editArtist != null ? editArtist.getText().toString().trim() : songArtist;
            String alb = editAlbum != null ? editAlbum.getText().toString().trim() : "";
            String trkNum = editNum != null ? editNum.getText().toString().trim() : "";
            String yrGenre = editYearGenre != null ? editYearGenre.getText().toString().trim() : "";
            String l = getCleanSnippetLyrics();

            if (t == null || t.isEmpty()) t = "Unknown Track";
            if (a == null || a.isEmpty()) a = "Unknown Artist";

            Song updated = new Song(t, a, songUri, 0);
            ArtworkColorHelper.invalidateCacheForSong(songUri.toString());
            SongMetadataManager.saveSongEditFull(this, updated, t, a, alb, trkNum, yrGenre, pendingCoverUri, l);
            Toast.makeText(this, "Saved to Lyrics_Synced/ (Original Backup preserved)", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
    }
}
