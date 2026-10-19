package com.thando1402.auralis;

import android.app.AlertDialog;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
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
        });

        findViewById(R.id.btn_save_track_header).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.btn_save_track_bottom).setOnClickListener(v -> saveAndFinish());
    }

    private void saveAndFinish() {
        if (songUri != null) {
            String t = editTitle != null ? editTitle.getText().toString().trim() : songTitle;
            String a = editArtist != null ? editArtist.getText().toString().trim() : songArtist;
            if (t.isEmpty()) t = "Unknown Track";
            if (a.isEmpty()) a = "Unknown Artist";
            Song dummy = new Song(t, a, songUri, 0);
            SongMetadataManager.saveSongEdit(this, dummy, t, a, pendingCoverUri);
            Toast.makeText(this, "Track details saved!", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}