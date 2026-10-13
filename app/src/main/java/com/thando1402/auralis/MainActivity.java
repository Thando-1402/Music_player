package com.thando1402.auralis;

import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.ComponentName;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private MediaController mediaController;
    private List<Song> currentSongList = new ArrayList<>();
    private RecyclerView recyclerSongs;
    private TextView miniTitle, miniSubtitle, textStatus;
    private AppCompatImageButton btnPlayPause, btnNext;
    private ShapeableImageView miniArt;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    textStatus.setText("Scanning library...");
                    fetchAudioFiles();
                } else {
                    textStatus.setText("Permission denied. We cannot access your music without it.");
                    Toast.makeText(this, "Audio permission is required to play local files.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerSongs = findViewById(R.id.recycler_view_songs);
        textStatus = findViewById(R.id.textStatus);
        
        miniTitle = findViewById(R.id.mini_track_title);
        miniSubtitle = findViewById(R.id.mini_artist_name);
        btnPlayPause = findViewById(R.id.mini_btn_play_pause);
        btnNext = findViewById(R.id.mini_btn_next);
        miniArt = findViewById(R.id.mini_album_art);

        recyclerSongs.setLayoutManager(new LinearLayoutManager(this));

        checkPermissionAndScan();

        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_library);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_library);
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        ListenableFuture<MediaController> controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                
                // Add the listener to update the UI when the song changes
                mediaController.addListener(new androidx.media3.common.Player.Listener() {
                    @Override
                    public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                        if (mediaItem != null && mediaItem.mediaMetadata != null) {
                            String title = mediaItem.mediaMetadata.title != null ? mediaItem.mediaMetadata.title.toString() : "Unknown Track";
                            String artist = mediaItem.mediaMetadata.artist != null ? mediaItem.mediaMetadata.artist.toString() : "Unknown Artist";
                            
                            miniTitle.setText(title);
                            miniSubtitle.setText(artist);
                        }
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying) {
                            btnPlayPause.setImageResource(R.drawable.ic_pause_24);
                        } else {
                            btnPlayPause.setImageResource(R.drawable.ic_play_arrow_24);
                        }
                    }
                });

                // Wire up the Play/Pause button
                btnPlayPause.setOnClickListener(v -> {
                    if (mediaController.isPlaying()) {
                        mediaController.pause();
                    } else {
                        mediaController.play();
                    }
                });

                // Wire up the Next button
                btnNext.setOnClickListener(v -> {
                    if (mediaController.hasNextMediaItem()) {
                        mediaController.seekToNextMediaItem();
                    }
                });

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mediaController != null) {
            mediaController.release();
            mediaController = null;
        }
    }

    private void checkPermissionAndScan() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                Manifest.permission.READ_MEDIA_AUDIO :
                Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            fetchAudioFiles();
        } else {
            requestPermissionLauncher.launch(permission);
        }
    }

    private void fetchAudioFiles() {
        // Run database query on a background thread so UI doesn't freeze
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            List<Song> songList = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) :
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

            try (Cursor cursor = getApplicationContext().getContentResolver().query(
                    collection, projection, selection, null, sortOrder)) {

                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String title = cursor.getString(titleColumn);
                        String artist = cursor.getString(artistColumn);
                        long duration = cursor.getLong(durationColumn);

                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

                        if (title == null || title.isEmpty()) title = "Unknown Track";
                        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";

                        songList.add(new Song(title, artist, contentUri, duration));
                    }
                }
            }

            // Push results back to the main UI thread
            handler.post(() -> {
                if (songList.isEmpty()) {
                    textStatus.setText("No music found on this device or SD card.");
                    textStatus.setVisibility(View.VISIBLE);
                    recyclerSongs.setVisibility(View.GONE);
                } else {
                    textStatus.setVisibility(View.GONE);
                    recyclerSongs.setVisibility(View.VISIBLE);
                    currentSongList = songList;
                    SongAdapter adapter = new SongAdapter(songList, position -> {
                        if (mediaController != null) {
                            mediaController.clearMediaItems();
                            for (Song s : currentSongList) {
                                mediaController.addMediaItem(MediaItem.fromUri(s.getUri()));
                            }
                            mediaController.seekToDefaultPosition(position);
                            mediaController.prepare();
                            mediaController.play();
                        }
                    });
                    recyclerSongs.setAdapter(adapter);

                    miniTitle.setText(songList.size() + " Tracks Indexed");
                    miniSubtitle.setText("Ready to play");
                }
            });
        });
    }
}