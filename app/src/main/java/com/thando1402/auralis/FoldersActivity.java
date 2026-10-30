package com.thando1402.auralis;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FoldersActivity extends AppCompatActivity {

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private TextView textStorageTitle, textStorageFree;
    private TextView textSdStorageTitle, textSdStorageFree;
    private ProgressBar progressStorage, progressSdStorage;
    private TextView textBreadcrumbs, textDirectoriesHeader, textLooseFilesHeader;
    private RecyclerView recyclerFolders, recyclerLooseFiles;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPlayPause, btnNext;
    private ShapeableImageView miniArt;
    private View miniPlayerContainer;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

    private final Map<String, List<Song>> folderSongsMap = new LinkedHashMap<>();
    private final Map<String, Long> folderBytesMap = new LinkedHashMap<>();
    private final List<FolderItem> allFolders = new ArrayList<>();
    private String selectedFolderPath = null;
    private boolean sortByName = true;
    private boolean initialScanTriggered = false;
    private long totalAudioBytes = 0;

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
        AuralisTelemetryManager.init(this);
        setContentView(R.layout.activity_folders);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_folders);
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_open_profile) {
                    startActivity(new Intent(this, ProfileSettingsActivity.class));
                    return true;
                }
                return false;
            });
        }

        textStorageTitle = findViewById(R.id.text_storage_title);
        textStorageFree = findViewById(R.id.text_storage_free);
        progressStorage = findViewById(R.id.progress_storage);

        textSdStorageTitle = findViewById(R.id.text_sd_storage_title);
        textSdStorageFree = findViewById(R.id.text_sd_storage_free);
        progressSdStorage = findViewById(R.id.progress_sd_storage);

        textBreadcrumbs = findViewById(R.id.text_path_breadcrumbs);
        textDirectoriesHeader = findViewById(R.id.text_directories_header);
        textLooseFilesHeader = findViewById(R.id.text_loose_files_header);

        MaterialButton btnUpDir = findViewById(R.id.btn_up_dir);
        MaterialButton btnScanMedia = findViewById(R.id.btn_scan_media);
        MaterialButton btnSortFolders = findViewById(R.id.btn_sort_folders);

        recyclerFolders = findViewById(R.id.recycler_folders);
        recyclerLooseFiles = findViewById(R.id.recycler_loose_files);
        recyclerFolders.setLayoutManager(new LinearLayoutManager(this));
        recyclerLooseFiles.setLayoutManager(new LinearLayoutManager(this));

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);
        miniPlayerContainer = findViewById(R.id.mini_player_folders);

        setupMiniPlayerControls();

        View cardTriggerExclusions = findViewById(R.id.card_trigger_exclusions);
        if (cardTriggerExclusions != null) {
            cardTriggerExclusions.setOnClickListener(v -> startActivity(new Intent(FoldersActivity.this, FolderExclusionsActivity.class)));
        }

        View cardStorageInternal = findViewById(R.id.card_storage_internal);
        if (cardStorageInternal != null) {
            cardStorageInternal.setOnClickListener(v -> showStorageAndTelemetryDialog());
        }

        View cardStorageMicrosd = findViewById(R.id.card_storage_microsd);
        if (cardStorageMicrosd != null) {
            cardStorageMicrosd.setOnClickListener(v -> showStorageAndTelemetryDialog());
        }

        btnUpDir.setOnClickListener(v -> {
            if (selectedFolderPath != null) {
                selectedFolderPath = null;
                renderCurrentDirectoryState();
            } else {
                Toast.makeText(this, "Already at root storage overview", Toast.LENGTH_SHORT).show();
            }
        });

        btnSortFolders.setOnClickListener(v -> {
            sortByName = !sortByName;
            btnSortFolders.setText(sortByName ? R.string.btn_sort_name : R.string.btn_sort_size);
            sortAndRender();
        });

        btnScanMedia.setOnClickListener(v -> {
            Toast.makeText(this, "Rescanning storage & MicroSD...", Toast.LENGTH_SHORT).show();
            List<String> pathsToScan = new ArrayList<>(folderSongsMap.keySet());
            pathsToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());

            MediaScannerConnection.scanFile(this,
                    pathsToScan.toArray(new String[0]),
                    null,
                    (path, uri) -> runOnUiThread(this::scanStorageAndAudioFolders));
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_folders);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_folders);

        initialScanTriggered = true;
        scanStorageAndAudioFolders();
    }

    private void setupMiniPlayerControls() {
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

    private void updateStorageCardUI() {
        AuralisTelemetryManager.StorageSnapshot snap = AuralisTelemetryManager.getStorageSnapshot(this);

        if (textStorageTitle != null) {
            textStorageTitle.setText(getString(R.string.storage_internal_pct_fmt, snap.internalUsedPercent));
        }
        if (textStorageFree != null) {
            textStorageFree.setText(getString(R.string.storage_free_total_fmt,
                    Formatter.formatShortFileSize(this, snap.internalFreeBytes),
                    Formatter.formatShortFileSize(this, snap.internalTotalBytes)));
        }
        if (progressStorage != null) {
            progressStorage.setProgress(snap.internalUsedPercent);
        }

        if (snap.hasSdCard) {
            if (textSdStorageTitle != null) {
                textSdStorageTitle.setText(getString(R.string.storage_microsd_pct_fmt, snap.sdUsedPercent));
            }
            if (textSdStorageFree != null) {
                textSdStorageFree.setText(getString(R.string.storage_free_total_fmt,
                        Formatter.formatShortFileSize(this, snap.sdFreeBytes),
                        Formatter.formatShortFileSize(this, snap.sdTotalBytes)));
            }
            if (progressSdStorage != null) progressSdStorage.setProgress(snap.sdUsedPercent);
        } else {
            if (textSdStorageTitle != null) textSdStorageTitle.setText(R.string.storage_microsd_short);
            if (textSdStorageFree != null) textSdStorageFree.setText(R.string.storage_not_mounted);
            if (progressSdStorage != null) progressSdStorage.setProgress(0);
        }
    }

    private void showStorageAndTelemetryDialog() {
        AuralisTelemetryManager.StorageSnapshot snap = AuralisTelemetryManager.getStorageSnapshot(this);
        File crashFile = AuralisTelemetryManager.getLastCrashLogFile(this);
        boolean hasCrashLog = crashFile.exists() && crashFile.length() > 0;
        int queuedOfflineReports = AuralisTelemetryManager.getQueuedCrashReportCount(this);

        String msg = "• Phone Free Space: " + Formatter.formatShortFileSize(this, snap.internalFreeBytes)
                + " / " + Formatter.formatShortFileSize(this, snap.internalTotalBytes) + "\n"
                + "• MicroSD Card: " + (snap.hasSdCard
                ? Formatter.formatShortFileSize(this, snap.sdFreeBytes) + " free / " + Formatter.formatShortFileSize(this, snap.sdTotalBytes)
                : "Not Detected") + "\n"
                + "• Indexed Audio Footprint: " + Formatter.formatShortFileSize(this, totalAudioBytes) + "\n"
                + "• Auralis APK Binary Size: " + AuralisTelemetryManager.getApkSizeFormatted(this) + "\n"
                + "• Active RAM Heap Usage: " + AuralisTelemetryManager.getRamUsageMb() + " MB\n"
                + "• Lyrics Backup Folder:\n  " + SongMetadataManager.getDedicatedLyricsBackupDir(this).getAbsolutePath() + "\n"
                + "• Crash Store-and-Forward: " + (AuralisTelemetryManager.isFirebaseLinked()
                ? "WorkManager -> Firebase Crashlytics"
                : "Local JSON Queue Active") + "\n"
                + "• Pending Offline Crash Reports: " + queuedOfflineReports
                + (hasCrashLog ? " (Log history saved)" : " (0 crashes)");

        boolean preferSd = SongMetadataManager.isPreferSdCard(this);

        new AlertDialog.Builder(this)
                .setTitle("Storage & Crash Telemetry")
                .setMessage(msg)
                .setPositiveButton(preferSd ? "Switch Cache to Internal" : "Switch Cache to MicroSD", (d, w) -> {
                    SongMetadataManager.setPreferSdCard(this, !preferSd);
                    updateStorageCardUI();
                    Toast.makeText(this, "Storage preference updated", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Filter Settings", (d, w) ->
                        startActivity(new Intent(this, FolderExclusionsActivity.class)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void scanStorageAndAudioFolders() {
        updateStorageCardUI();
        Handler handler = new Handler(Looper.getMainLooper());

        scanExecutor.execute(() -> {
            Map<String, List<Song>> groupedSongs = new LinkedHashMap<>();
            Map<String, Long> groupedBytes = new LinkedHashMap<>();
            long audioBytesSum = 0;

            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.MediaColumns.VOLUME_NAME
                    }
                    : new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.SIZE
                    };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

            try (Cursor cursor = getApplicationContext().getContentResolver().query(
                    collection, projection, selection, null, sortOrder)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    int volumeCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) : -1;

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String title = cursor.getString(titleCol);
                        String artist = cursor.getString(artistCol);
                        long duration = cursor.getLong(durCol);
                        String fullPath = cursor.getString(dataCol);
                        long fileSize = cursor.getLong(sizeCol);

                        if (AudioFilterManager.shouldExcludeTrack(getApplicationContext(), title, fullPath, duration)) {
                            continue;
                        }

                        audioBytesSum += Math.max(0, fileSize);

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
                        SongMetadataManager.applySavedEdits(getApplicationContext(), song, false);

                        String parentPath = "/storage/Music";
                        if (fullPath != null) {
                            File f = new File(fullPath);
                            if (f.getParent() != null) parentPath = f.getParent();
                        }

                        groupedSongs.computeIfAbsent(parentPath, k -> new ArrayList<>()).add(song);
                        Long prevBytes = groupedBytes.get(parentPath);
                        groupedBytes.put(parentPath, (prevBytes != null ? prevBytes : 0L) + Math.max(0, fileSize));
                    }
                }
            } catch (Exception ignored) {
            }

            final long finalAudioBytes = audioBytesSum;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                totalAudioBytes = finalAudioBytes;
                folderSongsMap.clear();
                folderSongsMap.putAll(groupedSongs);
                folderBytesMap.clear();
                folderBytesMap.putAll(groupedBytes);

                updateStorageCardUI();
                sortAndRender();
            });
        });
    }

    private void sortAndRender() {
        allFolders.clear();
        for (Map.Entry<String, List<Song>> entry : folderSongsMap.entrySet()) {
            String path = entry.getKey();
            String rawName = new File(path).getName();
            if (rawName.isEmpty()) rawName = path;

            String badge = AudioFilterManager.isMicroSdPath(path) ? "[MicroSD] " : "[Phone] ";
            Long rawBytes = folderBytesMap.get(path);
            long bytes = rawBytes != null ? rawBytes : 0L;
            allFolders.add(new FolderItem(badge + rawName, path, entry.getValue().size(), bytes));
        }

        if (sortByName) {
            allFolders.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        } else {
            allFolders.sort((a, b) -> Long.compare(b.getTotalBytes(), a.getTotalBytes()));
        }

        renderCurrentDirectoryState();
    }

    private void renderCurrentDirectoryState() {
        if (selectedFolderPath == null) {
            textBreadcrumbs.setText(R.string.breadcrumbs_default);
            if (textDirectoriesHeader != null) {
                textDirectoriesHeader.setText(getString(R.string.directories_header_fmt, allFolders.size()));
                textDirectoriesHeader.setVisibility(View.VISIBLE);
            }
            recyclerFolders.setVisibility(View.VISIBLE);
            if (textLooseFilesHeader != null) textLooseFilesHeader.setVisibility(View.GONE);
            recyclerLooseFiles.setVisibility(View.GONE);

            recyclerFolders.setAdapter(new FolderAdapter(allFolders, new FolderAdapter.OnFolderClickListener() {
                @Override
                public void onFolderClick(FolderItem folder) {
                    selectedFolderPath = folder.getPath();
                    renderCurrentDirectoryState();
                }

                @Override
                public void onFolderLongClick(FolderItem folder) {
                    new AlertDialog.Builder(FoldersActivity.this)
                            .setTitle("Hide Folder")
                            .setMessage("Hide \"" + folder.getName() + "\" from Auralis Library & Folders?")
                            .setPositiveButton("Hide", (dialog, which) -> {
                                AudioFilterManager.addExcludedFolder(FoldersActivity.this, folder.getPath());
                                scanStorageAndAudioFolders();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            }));
        } else {
            String folderName = new File(selectedFolderPath).getName();
            textBreadcrumbs.setText(getString(R.string.breadcrumbs_folder_fmt, folderName));
            if (textDirectoriesHeader != null) {
                textDirectoriesHeader.setVisibility(View.GONE);
            }
            recyclerFolders.setVisibility(View.GONE);

            if (textLooseFilesHeader != null) {
                textLooseFilesHeader.setVisibility(View.VISIBLE);
                textLooseFilesHeader.setText(getString(R.string.files_in_folder_fmt, folderName.toUpperCase(Locale.US)));
            }
            recyclerLooseFiles.setVisibility(View.VISIBLE);

            List<Song> folderSongs = folderSongsMap.getOrDefault(selectedFolderPath, new ArrayList<>());
            bindSongsList(folderSongs);
        }
    }

    private void bindSongsList(List<Song> songs) {
        SongAdapter adapter = new SongAdapter(songs, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(int position) {
                if (mediaController != null && position >= 0 && position < songs.size()) {
                    List<MediaItem> items = PlaybackStateHelper.buildAdaptiveQueue(songs);
                    mediaController.setMediaItems(items, position, 0);
                    mediaController.prepare();
                    mediaController.play();
                    syncMiniPlayerUI();
                }
            }

            @Override
            public void onSongOptionsClick(int position) {
                if (position < 0 || position >= songs.size()) return;
                Song s = songs.get(position);
                Intent intent = new Intent(FoldersActivity.this, EditTrackActivity.class);
                intent.putExtra("EXTRA_SONG_URI", s.getUri().toString());
                intent.putExtra("EXTRA_SONG_TITLE", s.getTitle());
                intent.putExtra("EXTRA_SONG_ARTIST", s.getArtist());
                startActivity(intent);
            }
        });
        recyclerLooseFiles.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (initialScanTriggered) {
            initialScanTriggered = false;
        } else {
            scanStorageAndAudioFolders();
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
