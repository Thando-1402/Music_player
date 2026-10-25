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
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SongAdapter.OnSongClickListener {

    private static final int PERMISSION_REQUEST_CODE = 123;

    private RecyclerView recyclerSongs;
    private SongAdapter adapter;
    private final List<Song> songList = new ArrayList<>();
    private final List<Song> displayedList = new ArrayList<>();

    private TextView textLibraryStats;
    private View emptyStateContainer;
    private SearchView searchViewLibrary;
    private boolean searchIsVisible = false;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPrev, btnPlayPause, btnNext;
    private ShapeableImageView miniArt;
    private View miniPlayerContainer;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private ExecutorService scanExecutor;
    private Handler mainHandler;

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
        setContentView(R.layout.activity_main);

        OpenRouterAiHelper.initFirebaseAppCheck(this);

        scanExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        recyclerSongs = findViewById(R.id.recycler_view_songs);
        recyclerSongs.setLayoutManager(new LinearLayoutManager(this));

        textLibraryStats = findViewById(R.id.text_library_stats);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        searchViewLibrary = findViewById(R.id.search_view_library);

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPrev = findViewById(R.id.mini_prev);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);
        miniPlayerContainer = findViewById(R.id.mini_player_main);

        MaterialToolbar topAppBar = findViewById(R.id.toolbar_main);
        if (topAppBar != null) {
            topAppBar.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_search) {
                    toggleSearchAnimation();
                    return true;
                } else if (id == R.id.action_open_profile) {
                    startActivity(new Intent(MainActivity.this, ProfileSettingsActivity.class));
                    return true;
                } else if (id == R.id.action_sort_title) {
                    Collections.sort(displayedList, (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Sorted by Title (A-Z)", Toast.LENGTH_SHORT).show();
                    return true;
                } else if (id == R.id.action_sort_artist) {
                    Collections.sort(displayedList, (a, b) -> a.getArtist().compareToIgnoreCase(b.getArtist()));
                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Sorted by Artist", Toast.LENGTH_SHORT).show();
                    return true;
                } else if (id == R.id.action_rescan) {
                    checkPermissionAndScan();
                    Toast.makeText(this, "Rescanning Phone & MicroSD...", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            });
        }

        setupSearchFilter();

        Chip chipAll = findViewById(R.id.chip_all_songs);
        if (chipAll != null) {
            chipAll.setOnClickListener(v -> {
                displayedList.clear();
                displayedList.addAll(songList);
                Collections.sort(displayedList, (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
                if (adapter != null) adapter.notifyDataSetChanged();
                updateLibraryStats();
            });
        }
        Chip chipAiMix = findViewById(R.id.chip_ai_smart_mix);
        if (chipAiMix != null) {
            chipAiMix.setOnClickListener(v -> {
                if (songList.isEmpty()) return;
                List<Song> mix = OpenRouterAiHelper.curateSmartMix(this, songList);
                displayedList.clear();
                displayedList.addAll(mix);
                if (adapter != null) adapter.notifyDataSetChanged();
                updateLibraryStats();
                onSongClick(0);
                Toast.makeText(this, "✨ AI Smart Mix curated (" + displayedList.size() + " tracks)", Toast.LENGTH_SHORT).show();
            });
        }
        Chip chipAlbums = findViewById(R.id.chip_albums);
        if (chipAlbums != null) {
            chipAlbums.setOnClickListener(v -> {
                startActivity(new Intent(MainActivity.this, PlaylistsActivity.class));
                overridePendingTransition(0, 0);
            });
        }
        Chip chipArtists = findViewById(R.id.chip_artists);
        if (chipArtists != null) {
            chipArtists.setOnClickListener(v -> {
                Collections.sort(displayedList, (a, b) -> a.getArtist().compareToIgnoreCase(b.getArtist()));
                if (adapter != null) adapter.notifyDataSetChanged();
                Toast.makeText(this, "Grouped by Artist", Toast.LENGTH_SHORT).show();
            });
        }
        Chip chipFolders = findViewById(R.id.chip_folders);
        if (chipFolders != null) {
            chipFolders.setOnClickListener(v -> {
                startActivity(new Intent(MainActivity.this, FoldersActivity.class));
                overridePendingTransition(0, 0);
            });
        }

        findViewById(R.id.btn_shuffle_all_library).setOnClickListener(v -> {
            if (mediaController != null && !displayedList.isEmpty()) {
                mediaController.setShuffleModeEnabled(true);
                onSongClick(0);
                Toast.makeText(this, "Shuffling library", Toast.LENGTH_SHORT).show();
            }
        });

        setupMiniPlayer();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_main);
        if (bottomNav != null) {
            NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_library);
        }

        checkPermissionAndScan();
    }

    private void toggleSearchAnimation() {
        if (searchViewLibrary == null) return;
        searchIsVisible = !searchIsVisible;
        if (searchIsVisible) {
            searchViewLibrary.setAlpha(0f);
            searchViewLibrary.setVisibility(View.VISIBLE);
            searchViewLibrary.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(250)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .start();
            searchViewLibrary.setIconified(false);
        } else {
            searchViewLibrary.animate()
                    .alpha(0f)
                    .translationY(-20f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        searchViewLibrary.setVisibility(View.GONE);
                        searchViewLibrary.setQuery("", false);
                    }).start();
        }
    }

    private void setupSearchFilter() {
        if (searchViewLibrary != null) {
            searchViewLibrary.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    return false;
                }
                @Override
                public boolean onQueryTextChange(String newText) {
                    filterLibrary(newText);
                    return true;
                }
            });
        }
    }

    private void filterLibrary(String query) {
        displayedList.clear();
        if (query == null || query.trim().isEmpty()) {
            displayedList.addAll(songList);
        } else {
            String lower = query.toLowerCase().trim();
            for (Song s : songList) {
                if ((s.getTitle() != null && s.getTitle().toLowerCase().contains(lower)) ||
                        (s.getArtist() != null && s.getArtist().toLowerCase().contains(lower))) {
                    displayedList.add(s);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateLibraryStats();
    }

    private void checkPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_AUDIO}, PERMISSION_REQUEST_CODE);
            } else {
                scanMedia();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            } else {
                scanMedia();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanMedia();
            } else {
                if (textLibraryStats != null) textLibraryStats.setText("Storage Permission Required");
            }
        }
    }

    private void scanMedia() {
        scanExecutor.execute(() -> {
            List<Song> newSongs = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.VOLUME_NAME
                    }
                    : new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.DURATION
                    };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

            try (Cursor cursor = getContentResolver().query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " ASC")) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int volumeColumn = cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String title = cursor.getString(titleColumn);
                        String artist = cursor.getString(artistColumn);
                        String data = cursor.getString(dataColumn);
                        long duration = cursor.getLong(durationColumn);

                        Uri contentUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeColumn != -1) {
                            String volName = cursor.getString(volumeColumn);
                            if (volName != null && !volName.isEmpty()) {
                                contentUri = MediaStore.Audio.Media.getContentUri(volName, id);
                            } else {
                                contentUri = ContentUris.withAppendedId(collection, id);
                            }
                        } else {
                            contentUri = ContentUris.withAppendedId(collection, id);
                        }

                        if (!AudioFilterManager.shouldExcludeTrack(this, title, data, duration)) {
                            Song temp = new Song(title, artist, contentUri, duration);
                            SongMetadataManager.applySavedEdits(this, temp);
                            newSongs.add(temp);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            mainHandler.post(() -> {
                songList.clear();
                songList.addAll(newSongs);
                displayedList.clear();
                displayedList.addAll(songList);

                if (adapter == null) {
                    adapter = new SongAdapter(displayedList, this);
                    recyclerSongs.setAdapter(adapter);
                    animateRecyclerViewEntrance();
                } else {
                    adapter.notifyDataSetChanged();
                }

                updateLibraryStats();
            });
        });
    }

    private void animateRecyclerViewEntrance() {
        if (recyclerSongs == null || recyclerSongs.getAdapter() == null) return;
        LayoutAnimationController controller = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_anim_fall_down);
        recyclerSongs.setLayoutAnimation(controller);
        recyclerSongs.getAdapter().notifyDataSetChanged();
        recyclerSongs.scheduleLayoutAnimation();
    }

    private void updateLibraryStats() {
        if (textLibraryStats != null) {
            textLibraryStats.setText("All Local Tracks (" + displayedList.size() + " files)");
        }
        Chip chipAll = findViewById(R.id.chip_all_songs);
        if (chipAll != null) {
            chipAll.setText("All Tracks (" + songList.size() + ")");
        }
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisibility(displayedList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onSongClick(int position) {
        if (mediaController != null && !displayedList.isEmpty()) {
            List<MediaItem> mediaItems = new ArrayList<>();
            for (Song song : displayedList) {
                MediaItem.Builder builder = new MediaItem.Builder()
                        .setUri(song.getUri())
                        .setMediaId(song.getUri().toString());

                MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                        .setTitle(song.getTitle())
                        .setArtist(song.getArtist());

                if (song.getCustomCoverUri() != null) {
                    metaBuilder.setArtworkUri(song.getCustomCoverUri());
                }
                builder.setMediaMetadata(metaBuilder.build());
                mediaItems.add(builder.build());
            }

            mediaController.setMediaItems(mediaItems, position, 0);
            mediaController.prepare();
            mediaController.play();
            syncMiniPlayerUI();
            bounceMiniPlayer();
        }
    }

    @Override
    public void onSongOptionsClick(int position) {
        Song clicked = displayedList.get(position);
        Intent editIntent = new Intent(this, EditTrackActivity.class);
        editIntent.putExtra("EXTRA_SONG_URI", clicked.getUri().toString());
        editIntent.putExtra("EXTRA_SONG_TITLE", clicked.getTitle());
        editIntent.putExtra("EXTRA_SONG_ARTIST", clicked.getArtist());
        startActivity(editIntent);
    }

    @Override
    public void onSongAiCleanClick(int position) {
        if (position < 0 || position >= displayedList.size()) return;
        Song target = displayedList.get(position);
        Toast.makeText(this, "✨ Dual-AI cleaning tags for \"" + target.getTitle() + "\"...", Toast.LENGTH_SHORT).show();
        OpenRouterAiHelper.autoCleanSongMetadata(this, target.getTitle(), target.getArtist(), (cleanTitle, cleanArtist, usedCloudAi) -> {
            target.setTitle(cleanTitle);
            target.setArtist(cleanArtist);
            Song dummy = new Song(cleanTitle, cleanArtist, target.getUri(), target.getDuration());
            SongMetadataManager.saveSongEdit(this, dummy, cleanTitle, cleanArtist, target.getCustomCoverUri(), target.getLyrics());
            if (adapter != null) adapter.notifyItemChanged(position);
            Toast.makeText(this, (usedCloudAi ? "✨ Cloud AI: " : "✨ Smart Parser: ") + cleanTitle + " — " + cleanArtist, Toast.LENGTH_SHORT).show();
        });
    }

    private void bounceMiniPlayer() {
        if (miniPlayerContainer != null) {
            miniPlayerContainer.animate()
                    .scaleX(0.97f).scaleY(0.97f)
                    .setDuration(100)
                    .withEndAction(() -> miniPlayerContainer.animate()
                            .scaleX(1.0f).scaleY(1.0f)
                            .setDuration(250)
                            .setInterpolator(new OvershootInterpolator(2.0f))
                            .start()).start();
        }
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
        if (miniPlayerContainer != null) {
            miniPlayerContainer.setOnClickListener(v -> {
                startActivity(new Intent(MainActivity.this, NowPlayingActivity.class));
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
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            checkPermissionAndScan();
        }
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
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }
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