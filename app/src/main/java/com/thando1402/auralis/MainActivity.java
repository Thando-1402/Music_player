package com.thando1402.auralis;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.widget.EditText;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private boolean initialScanTriggered = false;
    private long lastAppliedLibraryVersion = -1L;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPlayPause, btnNext;
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
                    displayedList.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
                    refreshAdapterDataset();
                    Toast.makeText(this, R.string.menu_sort_title, Toast.LENGTH_SHORT).show();
                    return true;
                } else if (id == R.id.action_sort_artist) {
                    displayedList.sort((a, b) -> a.getArtist().compareToIgnoreCase(b.getArtist()));
                    refreshAdapterDataset();
                    Toast.makeText(this, R.string.menu_sort_artist, Toast.LENGTH_SHORT).show();
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
                displayedList.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
                refreshAdapterDataset();
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
                refreshAdapterDataset();
                updateLibraryStats();
                onSongClick(0);
                Toast.makeText(this, "✨ AI Smart Mix curated (" + displayedList.size() + " tracks)", Toast.LENGTH_SHORT).show();
            });
        }
        Chip chipAlbums = findViewById(R.id.chip_albums);
        if (chipAlbums != null) {
            chipAlbums.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, PlaylistsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                NavigationHelper.applySlideTransition(MainActivity.this, true);
            });
        }
        Chip chipArtists = findViewById(R.id.chip_artists);
        if (chipArtists != null) {
            chipArtists.setOnClickListener(v -> {
                displayedList.sort((a, b) -> a.getArtist().compareToIgnoreCase(b.getArtist()));
                refreshAdapterDataset();
                Toast.makeText(this, "Grouped by Artist", Toast.LENGTH_SHORT).show();
            });
        }
        Chip chipFolders = findViewById(R.id.chip_folders);
        if (chipFolders != null) {
            chipFolders.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, FoldersActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                NavigationHelper.applySlideTransition(MainActivity.this, true);
            });
        }

        View btnShuffleAll = findViewById(R.id.btn_shuffle_all_library);
        btnShuffleAll.setOnClickListener(v -> {
            if (mediaController != null && !displayedList.isEmpty()) {
                mediaController.setShuffleModeEnabled(true);
                onSongClick(0);
                Toast.makeText(this, "Shuffling library", Toast.LENGTH_SHORT).show();
            }
        });
        UiAnimationHelper.attachSpringTouch(btnShuffleAll);

        setupMiniPlayer();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_main);
        if (bottomNav != null) {
            NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_library);
        }

        initialScanTriggered = true;
        checkPermissionAndScan();
        promptWelcomeProfileSetupIfNeeded();
    }

    private void promptWelcomeProfileSetupIfNeeded() {
        if (!OpenRouterAiHelper.shouldShowProfileWelcome(this)) return;
        OpenRouterAiHelper.markProfileWelcomeShown(this);

        EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.setup_display_name_hint);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        nameInput.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setup_welcome_title)
                .setMessage(R.string.setup_welcome_subtitle)
                .setView(nameInput)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String entered = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                    if (!entered.isEmpty()) {
                        OpenRouterAiHelper.setProfileName(this, entered);
                        Toast.makeText(this, "Welcome, " + entered + "!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Cloud Sign-In", (d, w) ->
                        startActivity(new Intent(this, ProfileSetupActivity.class)))
                .setNegativeButton("Skip", null)
                .show();
    }

    private void toggleSearchAnimation() {
        if (searchViewLibrary == null) return;
        searchIsVisible = !searchIsVisible;
        searchViewLibrary.animate().cancel();
        if (searchIsVisible) {
            searchViewLibrary.setAlpha(0f);
            searchViewLibrary.setTranslationY(-16f);
            searchViewLibrary.setVisibility(View.VISIBLE);
            searchViewLibrary.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(UiAnimationHelper.OVERSHOOT)
                    .withLayer()
                    .start();
            searchViewLibrary.setIconified(false);
        } else {
            searchViewLibrary.animate()
                    .alpha(0f)
                    .translationY(-16f)
                    .setDuration(150)
                    .setInterpolator(UiAnimationHelper.DECELERATE)
                    .withLayer()
                    .withEndAction(() -> {
                        searchViewLibrary.setVisibility(View.GONE);
                        searchViewLibrary.setQuery("", false);
                    })
                    .start();
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
            String lower = query.toLowerCase(Locale.US).trim();
            for (Song s : songList) {
                if ((s.getTitle() != null && s.getTitle().toLowerCase(Locale.US).contains(lower)) ||
                        (s.getArtist() != null && s.getArtist().toLowerCase(Locale.US).contains(lower))) {
                    displayedList.add(s);
                }
            }
        }
        refreshAdapterDataset();
        updateLibraryStats();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshAdapterDataset() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void checkPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            List<String> needed = new ArrayList<>();
            boolean hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasAudio) {
                needed.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED && initialScanTriggered) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (hasAudio) {
                scanMedia();
            }
            if (!needed.isEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
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
            String audioPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED) {
                scanMedia();
            } else {
                if (textLibraryStats != null) textLibraryStats.setText(R.string.storage_permission_required);
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
                    int volumeColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME) : -1;

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String title = cursor.getString(titleColumn);
                        String artist = cursor.getString(artistColumn);
                        String data = cursor.getString(dataColumn);
                        long duration = cursor.getLong(durationColumn);

                        // Safe for Android 10 (API 29) and Android 11+ (API 30+): uses 1-arg getContentUri + withAppendedId
                        Uri contentUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeColumn != -1) {
                            String volName = cursor.getString(volumeColumn);
                            if (volName != null && !volName.isEmpty()) {
                                contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volName), id);
                            } else {
                                contentUri = ContentUris.withAppendedId(collection, id);
                            }
                        } else {
                            contentUri = ContentUris.withAppendedId(collection, id);
                        }

                        if (!AudioFilterManager.shouldExcludeTrack(getApplicationContext(), title, data, duration)) {
                            Song temp = new Song(title, artist, contentUri, duration);
                            SongMetadataManager.applySavedEdits(getApplicationContext(), temp, false);
                            newSongs.add(temp);
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            final long scannedVersion = NavigationHelper.getLibraryMutationVersion();
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                lastAppliedLibraryVersion = scannedVersion;
                songList.clear();
                songList.addAll(newSongs);
                displayedList.clear();
                displayedList.addAll(songList);

                if (adapter == null) {
                    adapter = new SongAdapter(displayedList, this);
                    recyclerSongs.setAdapter(adapter);
                    animateRecyclerViewEntrance();
                } else {
                    refreshAdapterDataset();
                }

                updateLibraryStats();
            });
        });
    }

    private void animateRecyclerViewEntrance() {
        if (recyclerSongs == null || recyclerSongs.getAdapter() == null) return;
        LayoutAnimationController controller = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_anim_fall_down);
        recyclerSongs.setLayoutAnimation(controller);
        refreshAdapterDataset();
        recyclerSongs.scheduleLayoutAnimation();
    }

    private void updateLibraryStats() {
        if (textLibraryStats != null) {
            int count = displayedList.size();
            textLibraryStats.setText(getResources().getQuantityString(R.plurals.library_stats_plural, count, count));
        }
        Chip chipAll = findViewById(R.id.chip_all_songs);
        if (chipAll != null) {
            chipAll.setText(getString(R.string.chip_all_tracks_fmt, songList.size()));
        }
        if (emptyStateContainer != null) {
            emptyStateContainer.setVisibility(displayedList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onSongClick(int position) {
        if (mediaController != null && position >= 0 && position < displayedList.size()) {
            List<MediaItem> mediaItems = PlaybackStateHelper.buildAdaptiveQueue(displayedList);
            mediaController.setMediaItems(mediaItems, position, 0);
            mediaController.prepare();
            mediaController.play();
            syncMiniPlayerUI();
            bounceMiniPlayer();
        }
    }

    @Override
    public void onSongOptionsClick(int position) {
        if (position < 0 || position >= displayedList.size()) return;
        Song clicked = displayedList.get(position);
        Intent editIntent = new Intent(this, EditTrackActivity.class);
        editIntent.putExtra("EXTRA_SONG_URI", clicked.getUri().toString());
        editIntent.putExtra("EXTRA_SONG_TITLE", clicked.getTitle());
        editIntent.putExtra("EXTRA_SONG_ARTIST", clicked.getArtist());
        startActivity(editIntent);
        NavigationHelper.applySlideTransition(this, true);
    }

    @Override
    public void onSongAiCleanClick(int position) {
        if (position < 0 || position >= displayedList.size()) return;
        Song target = displayedList.get(position);
        final android.content.Context appCtx = getApplicationContext();
        Toast.makeText(this, "✨ Dual-AI cleaning tags for \"" + target.getTitle() + "\"...", Toast.LENGTH_SHORT).show();
        OpenRouterAiHelper.autoCleanSongMetadata(this, target.getTitle(), target.getArtist(), (cleanTitle, cleanArtist, usedCloudAi) -> {
            if (isFinishing() || isDestroyed()) return;
            target.setTitle(cleanTitle);
            target.setArtist(cleanArtist);
            if (adapter != null) adapter.notifyItemChanged(position);
            scanExecutor.execute(() -> {
                SongMetadataManager.saveSongEdit(appCtx, target, cleanTitle, cleanArtist, target.getCustomCoverUri(), target.getLyrics());
                NavigationHelper.markLibraryDirty();
            });
            Toast.makeText(this, (usedCloudAi ? "✨ Cloud AI: " : "✨ Smart Parser: ") + cleanTitle + " — " + cleanArtist, Toast.LENGTH_SHORT).show();
        });
    }

    private void bounceMiniPlayer() {
        if (miniPlayerContainer != null) {
            miniPlayerContainer.animate().cancel();
            miniPlayerContainer.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(90)
                    .setInterpolator(UiAnimationHelper.DECELERATE)
                    .withLayer()
                    .withEndAction(() -> miniPlayerContainer.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(220)
                            .setInterpolator(UiAnimationHelper.OVERSHOOT)
                            .withLayer()
                            .start())
                    .start();
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
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToNext();
            });
        }
        if (miniPlayerContainer != null) {
            miniPlayerContainer.setOnClickListener(v -> UiAnimationHelper.openNowPlaying(this));
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
            } catch (ExecutionException | InterruptedException | CancellationException ignored) {
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (initialScanTriggered) {
            initialScanTriggered = false;
        } else if (songList.isEmpty() || lastAppliedLibraryVersion != NavigationHelper.getLibraryMutationVersion()) {
            checkPermissionAndScan();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mediaController != null) {
            PlaybackStateHelper.saveLastPlayedState(this, mediaController);
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
