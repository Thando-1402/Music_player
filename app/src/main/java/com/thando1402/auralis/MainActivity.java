package com.thando1402.auralis;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Button;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private List<Song> currentSongList = new ArrayList<>();

    private RecyclerView recyclerSongs;
    private TextView miniTitle, miniSubtitle, textStatus;
    private AppCompatImageButton btnPrev, btnPlayPause, btnNext;
    private ShapeableImageView miniArt;

    private Uri pendingCoverUri = null;
    private ImageView currentPreviewImageView = null;
    
    private final ActivityResultLauncher<String> pickCoverLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingCoverUri = uri;
                    if (currentPreviewImageView != null) {
                        currentPreviewImageView.setImageURI(uri);
                    }
                }
            });

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

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    if (textStatus != null) textStatus.setText("Scanning library...");
                    fetchAudioFiles();
                } else {
                    if (textStatus != null) textStatus.setText("Permission denied. We cannot access your music without it.");
                    Toast.makeText(this, "Audio permission is required to play local files.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AuralisTelemetryManager.init(this);
        setContentView(R.layout.activity_main);

        recyclerSongs = findViewById(R.id.recycler_view_songs);
        textStatus = findViewById(R.id.textStatus);

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPrev = findViewById(R.id.mini_prev);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);

        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(R.drawable.ic_play_arrow_24);
            btnPlayPause.setOnClickListener(v -> {
                if (mediaController != null) {
                    if (mediaController.isPlaying()) {
                        mediaController.pause();
                    } else {
                        mediaController.play();
                    }
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

        View miniPlayerContainer = findViewById(R.id.mini_player_container);
        if (miniPlayerContainer != null) {
            miniPlayerContainer.setOnClickListener(v -> {
                startActivity(new Intent(MainActivity.this, NowPlayingActivity.class));
                overridePendingTransition(R.anim.slide_up_in, 0);
            });
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_library);
        if (bottomNav != null) {
            NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_library);
        }

        recyclerSongs.setLayoutManager(new LinearLayoutManager(this));
        checkPermissionAndScan();
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
            } catch (ExecutionException | InterruptedException | java.util.concurrent.CancellationException e) {
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
        scanExecutor.shutdownNow();
    }

    private void syncMiniPlayerUI() {
        if (mediaController == null) return;
        MediaItem currentItem = mediaController.getCurrentMediaItem();
        if (currentItem != null && currentItem.mediaMetadata != null) {
            CharSequence title = currentItem.mediaMetadata.title;
            CharSequence artist = currentItem.mediaMetadata.artist;
            if (miniTitle != null && title != null && title.length() > 0) {
                miniTitle.setText(title);
            }
            if (miniSubtitle != null && artist != null && artist.length() > 0) {
                miniSubtitle.setText(artist);
            }
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
        Handler handler = new Handler(Looper.getMainLooper());

        scanExecutor.execute(() -> {
            List<Song> songList = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) :
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.MediaColumns.VOLUME_NAME
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
                    int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int volumeCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) : -1;

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String title = cursor.getString(titleColumn);
                        String artist = cursor.getString(artistColumn);
                        long duration = cursor.getLong(durationColumn);
                        String fullPath = cursor.getString(dataColumn);

                        if (AudioFilterManager.shouldExcludeTrack(MainActivity.this, title, fullPath)) {
                            continue;
                        }

                        Uri contentUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeCol != -1) {
                            String volumeName = cursor.getString(volumeCol);
                            if (volumeName != null && !volumeName.isEmpty()) {
                                contentUri = ContentUris.withAppendedId(
                                        MediaStore.Audio.Media.getContentUri(volumeName), id);
                            } else {
                                contentUri = ContentUris.withAppendedId(
                                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            }
                        } else {
                            contentUri = ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        }

                        if (title == null || title.isEmpty()) title = "Unknown Track";
                        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";

                        Song song = new Song(title, artist, contentUri, duration);
                        SongMetadataManager.applySavedEdits(MainActivity.this, song);
                        songList.add(song);
                    }
                }
            }

            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (songList.isEmpty()) {
                    if (textStatus != null) {
                        textStatus.setText("No music found on this device or SD card.");
                        textStatus.setVisibility(View.VISIBLE);
                    }
                    recyclerSongs.setVisibility(View.GONE);
                } else {
                    if (textStatus != null) textStatus.setVisibility(View.GONE);
                    recyclerSongs.setVisibility(View.VISIBLE);
                    currentSongList = songList;
                    SongAdapter adapter = new SongAdapter(songList, new SongAdapter.OnSongClickListener() {
                        @Override
                        public void onSongClick(int position) {
                            if (mediaController != null) {
                                mediaController.clearMediaItems();
                                for (Song s : currentSongList) {
                                    MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                                            .setTitle(s.getTitle())
                                            .setArtist(s.getArtist());
                                    if (s.getCustomCoverUri() != null) {
                                        metaBuilder.setArtworkUri(s.getCustomCoverUri());
                                    }
                                    MediaItem item = new MediaItem.Builder()
                                            .setUri(s.getUri())
                                            .setMediaId(s.getUri().toString())
                                            .setMediaMetadata(metaBuilder.build())
                                            .build();
                                    mediaController.addMediaItem(item);
                                }
                                mediaController.seekToDefaultPosition(position);
                                mediaController.prepare();
                                mediaController.play();
                            }
                        }

                        @Override
                        public void onSongOptionsClick(int position) {
                            Song s = currentSongList.get(position);
                            Intent intent = new Intent(MainActivity.this, EditTrackActivity.class);
                            intent.putExtra("EXTRA_SONG_URI", s.getUri().toString());
                            intent.putExtra("EXTRA_SONG_TITLE", s.getTitle());
                            intent.putExtra("EXTRA_SONG_ARTIST", s.getArtist());
                            startActivity(intent);
                        }
                    });
                    recyclerSongs.setAdapter(adapter);

                    if (mediaController == null || mediaController.getCurrentMediaItem() == null) {
                        if (miniTitle != null) miniTitle.setText(songList.size() + " Tracks Indexed");
                        if (miniSubtitle != null) miniSubtitle.setText("Ready to play");
                    }
                }
            });
        });
    }

    private void showEditDialog(Song song, int position) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_song, null);
        EditText editTitle = dialogView.findViewById(R.id.edit_song_title);
        EditText editArtist = dialogView.findViewById(R.id.edit_song_artist);
        Button btnPickCover = dialogView.findViewById(R.id.btn_pick_cover);
        currentPreviewImageView = dialogView.findViewById(R.id.img_edit_cover_preview);
        pendingCoverUri = null;

        editTitle.setText(song.getTitle());
        editArtist.setText(song.getArtist());
        if (song.getCustomCoverUri() != null) {
            currentPreviewImageView.setImageURI(song.getCustomCoverUri());
        }

        btnPickCover.setOnClickListener(v -> pickCoverLauncher.launch("image/*"));

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newTitle = editTitle.getText().toString();
                    String newArtist = editArtist.getText().toString();
                    SongMetadataManager.saveSongEdit(this, song, newTitle, newArtist, pendingCoverUri);
                    
                    if (recyclerSongs.getAdapter() != null) {
                        recyclerSongs.getAdapter().notifyItemChanged(position);
                    }
                    
                    if (mediaController != null) {
                        MediaItem currentItem = mediaController.getCurrentMediaItem();
                        if (currentItem != null && currentItem.localConfiguration != null 
                            && currentItem.localConfiguration.uri.equals(song.getUri())) {
                            
                            syncMiniPlayerUI();
                            if (miniArt != null && song.getCustomCoverUri() != null) {
                                miniArt.setImageURI(song.getCustomCoverUri());
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}