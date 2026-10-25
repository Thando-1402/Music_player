package com.thando1402.auralis;

import android.app.AlertDialog;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;
import java.io.FileOutputStream;

public class EditTrackActivity extends AppCompatActivity {

    private Uri songUri;
    private String songTitle;
    private String songArtist;

    private EditText editTitle, editArtist, editAlbum, editNum, editYearGenre;
    private TextView snippetLrc, textFileSpecs;
    private ShapeableImageView coverPreview;

    private Uri pendingCoverUri = null;

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
                    try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                         java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append("\n");
                        if (snippetLrc != null) snippetLrc.setText(sb.toString().trim());
                        Toast.makeText(this, "Lyrics loaded from file", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to read .lrc file", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_track);

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
        textFileSpecs = findViewById(R.id.text_track_file_specs);
        coverPreview = findViewById(R.id.edit_track_artwork);

        if (songUri != null) {
            Song temp = new Song(songTitle != null ? songTitle : "", songArtist != null ? songArtist : "", songUri, 0);
            SongMetadataManager.applySavedEdits(this, temp);
            if (editTitle != null) editTitle.setText(temp.getTitle());
            if (editArtist != null) editArtist.setText(temp.getArtist());
            if (temp.getCustomCoverUri() != null && coverPreview != null) {
                coverPreview.setImageURI(temp.getCustomCoverUri());
            }
            if (snippetLrc != null && temp.getLyrics() != null && !temp.getLyrics().isEmpty()) {
                snippetLrc.setText(temp.getLyrics());
            }
            if (textFileSpecs != null) {
                textFileSpecs.setText("URI: " + songUri.toString());
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
            new AlertDialog.Builder(this)
                    .setTitle("Lyrics Option")
                    .setItems(new CharSequence[]{"Type / Paste Text", "Pick .lrc File"}, (dialog, which) -> {
                        if (which == 0) {
                            EditText input = new EditText(this);
                            input.setHint("Paste .lrc or text lyrics here");
                            if (snippetLrc != null && !snippetLrc.getText().toString().startsWith("No lyrics")) {
                                input.setText(snippetLrc.getText().toString());
                            }
                            new AlertDialog.Builder(this)
                                    .setTitle("Edit Lyrics")
                                    .setView(input)
                                    .setPositiveButton("Apply", (d, w) -> {
                                        if (snippetLrc != null) snippetLrc.setText(input.getText().toString());
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else {
                            pickLrcLauncher.launch("*/*");
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
                    if (editTitle != null) editTitle.setText(cleanTitle);
                    if (editArtist != null) editArtist.setText(cleanArtist);
                    Toast.makeText(this, usedCloudAi ? "Cleaned via OpenRouter AI!" : "Cleaned via Offline Smart Parser!", Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private void saveAndFinish() {
        if (songUri != null) {
            String t = editTitle != null ? editTitle.getText().toString().trim() : songTitle;
            String a = editArtist != null ? editArtist.getText().toString().trim() : songArtist;
            String l = (snippetLrc != null && !snippetLrc.getText().toString().startsWith("No lyrics")) ? snippetLrc.getText().toString() : "";
            
            if (t.isEmpty()) t = "Unknown Track";
            if (a.isEmpty()) a = "Unknown Artist";
            Song dummy = new Song(t, a, songUri, 0);
            SongMetadataManager.saveSongEdit(this, dummy, t, a, pendingCoverUri, l);
            Toast.makeText(this, "Track details & lyrics saved!", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}