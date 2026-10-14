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

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FoldersActivity extends AppCompatActivity {

    private TextView textStorageTitle, textStorageFree, textStorageAllocated, textStorageStatus;
    private TextView textSdStorageTitle, textSdStorageFree;
    private ProgressBar progressStorage, progressSdStorage;
    private TextView textBreadcrumbs, textDirectoriesHeader, textLooseFilesHeader;
    private MaterialButton btnUpDir, btnScanMedia, btnSortFolders;
    private RecyclerView recyclerFolders, recyclerLooseFiles;

    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnPrev, btnPlayPause, btnNext;
    private ShapeableImageView miniArt;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

    private final Map<String, List<Song>> folderSongsMap = new LinkedHashMap<>();
    private final Map<String, Long> folderBytesMap = new LinkedHashMap<>();
    private final List<FolderItem> allFolders = new ArrayList<>();
    private String selectedFolderPath = null; // null = root overview of all audio folders
    private boolean sortByName = true;
    private long totalAudioBytes = 0;

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
        AuralisTelemetryManager.init(this);
        setContentView(R.layout.activity_folders);

        textStorageTitle = findViewById(R.id.text_storage_title);
        textStorageFree = findViewById(R.id.text_storage_free);
        textStorageAllocated = findViewById(R.id.text_storage_allocated);
        textStorageStatus = findViewById(R.id.text_storage_status);
        progressStorage = findViewById(R.id.progress_storage);

        textSdStorageTitle = findViewById(R.id.text_sd_storage_title);
        textSdStorageFree = findViewById(R.id.text_sd_storage_free);
        progressSdStorage = findViewById(R.id.progress_sd_storage);

        textBreadcrumbs = findViewById(R.id.text_path_breadcrumbs);
        textDirectoriesHeader = findViewById(R.id.text_directories_header);
        textLooseFilesHeader = findViewById(R.id.text_loose_files_header);

        btnUpDir = findViewById(R.id.btn_up_dir);
        btnScanMedia = findViewById(R.id.btn_scan_media);
        btnSortFolders = findViewById(R.id.btn_sort_folders);

        recyclerFolders = findViewById(R.id.recycler_folders);
        recyclerLooseFiles = findViewById(R.id.recycler_loose_files);
        recyclerFolders.setLayoutManager(new LinearLayoutManager(this));
        recyclerLooseFiles.setLayoutManager(new LinearLayoutManager(this));

        // Bind Mini-Player
        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPrev = findViewById(R.id.mini_prev);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);

        setupMiniPlayerControls();

        View cardStorage = findViewById(R.id.card_storage);
        if (cardStorage != null) {
            cardStorage.setOnClickListener(v -> showStorageAndTelemetryDialog());
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
            btnSortFolders.setText(sortByName ? "Name" : "Size");
            sortAndRender();
        });

        btnScanMedia.setOnClickListener(v -> {
            Toast.makeText(this, "Rescanning storage & MicroSD...", Toast.LENGTH_SHORT).show();
            MediaScannerConnection.scanFile(this,
                    new String[]{Environment.getExternalStorageDirectory().getAbsolutePath()},
                    null,
                    (path, uri) -> runOnUiThread(this::scanStorageAndAudioFolders));
            scanStorageAndAudioFolders();
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_folders);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_folders);

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
        View miniContainer = findViewById(R.id.mini_player_folders);
        if (miniContainer != null) {
            miniContainer.setOnClickListener(v -> {
                startActivity(new Intent(FoldersActivity.this, NowPlayingActivity.class));
                overridePendingTransition(R.anim.slide_up_in, 0);
            });
        }
    }

    private void updateStorageCardUI() {
        AuralisTelemetryManager.StorageSnapshot snap = AuralisTelemetryManager.getStorageSnapshot(this);
        
        String apkSizeStr = AuralisTelemetryManager.getApkSizeFormatted(this);
        long ramMb = AuralisTelemetryManager.getRamUsageMb();
        String audioSizeStr = Formatter.formatShortFileSize(this, totalAudioBytes);

        if (textStorageTitle != null) textStorageTitle.setText("Phone Internal (" + snap.internalUsedPercent + "% used)");
        if (textStorageFree != null) textStorageFree.setText(Formatter.formatShortFileSize(this, snap.internalFreeBytes) + " free / " + Formatter.formatShortFileSize(this, snap.internalTotalBytes));
        if (progressStorage != null) progressStorage.setProgress(snap.internalUsedPercent);

        if (snap.hasSdCard) {
            if (textSdStorageTitle != null) textSdStorageTitle.setText("MicroSD Card (" + snap.sdUsedPercent + "% used)");
            if (textSdStorageFree != null) textSdStorageFree.setText(Formatter.formatShortFileSize(this, snap.sdFreeBytes) + " free / " + Formatter.formatShortFileSize(this, snap.sdTotalBytes));
            if (progressSdStorage != null) progressSdStorage.setProgress(snap.sdUsedPercent);
        } else {
            if (textSdStorageTitle != null) textSdStorageTitle.setText("MicroSD Card");
            if (textSdStorageFree != null) textSdStorageFree.setText("No MicroSD Detected");
            if (progressSdStorage != null) progressSdStorage.setProgress(0);
        }

        if (textStorageAllocated != null) {
            textStorageAllocated.setText("Audio: " + audioSizeStr + " • APK: " + apkSizeStr + " • RAM: " + ramMb + "MB");
        }
    }

    private void showStorageAndTelemetryDialog() {
        AuralisTelemetryManager.StorageSnapshot snap = AuralisTelemetryManager.getStorageSnapshot(this);
        File crashFile = AuralisTelemetryManager.getLastCrashLogFile(this);
        boolean hasCrashLog = crashFile.exists() && crashFile.length() > 0;

        StringBuilder msg = new StringBuilder();
        msg.append("• Phone Free Space: ").append(Formatter.formatShortFileSize(this, snap.internalFreeBytes))
           .append(" / ").append(Formatter.formatShortFileSize(this, snap.internalTotalBytes)).append("\n");
        msg.append("• MicroSD Card: ").append(snap.hasSdCard ? Formatter.formatShortFileSize(this, snap.sdFreeBytes) + " free / " + Formatter.formatShortFileSize(this, snap.sdTotalBytes) : "Not Detected").append("\n");
        msg.append("• Indexed Audio Footprint: ").append(Formatter.formatShortFileSize(this, totalAudioBytes)).append("\n");
        msg.append("• Auralis APK Binary Size: ").append(AuralisTelemetryManager.getApkSizeFormatted(this)).append("\n");
        msg.append("• Active RAM Heap Usage: ").append(AuralisTelemetryManager.getRamUsageMb()).append(" MB / 45 MB Cap\n");
        msg.append("• Edits & Cover Storage Path:\n  ").append(SongMetadataManager.getStorageDirectory(this).getAbsolutePath()).append("\n");
        msg.append("• Crash Telemetry: ").append(AuralisTelemetryManager.isFirebaseLinked()
                ? "Firebase Crashlytics + Local SD Log"
                : "Local SD Crash Guard Active").append("\n");
        msg.append("• Recorded Crashes: ").append(hasCrashLog ? "Yes (Saved to log)" : "0 crashes recorded");

        boolean preferSd = SongMetadataManager.isPreferSdCard(this);

        new AlertDialog.Builder(this)
                .setTitle("Storage & Crash Telemetry")
                .setMessage(msg.toString())
                .setPositiveButton(preferSd ? "Switch Cache to Internal" : "Switch Cache to MicroSD", (d, w) -> {
                    SongMetadataManager.setPreferSdCard(this, !preferSd);
                    updateStorageCardUI();
                    Toast.makeText(this, "Storage preference updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void scanStorageAndAudioFolders() {
        updateStorageCardUI();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            Map<String, List<Song>> groupedSongs = new LinkedHashMap<>();
            Map<String, Long> groupedBytes = new LinkedHashMap<>();
            long audioBytesSum = 0;

            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) :
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = new String[]{
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

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String title = cursor.getString(titleCol);
                        String artist = cursor.getString(artistCol);
                        long duration = cursor.getLong(durCol);
                        String fullPath = cursor.getString(dataCol);
                        long fileSize = cursor.getLong(sizeCol);

                        audioBytesSum += Math.max(0, fileSize);
                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        if (title == null || title.isEmpty()) title = "Unknown Track";
                        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";

                        Song song = new Song(title, artist, contentUri, duration);
                        SongMetadataManager.applySavedEdits(FoldersActivity.this, song);

                        String parentPath = "/storage/Music";
                        if (fullPath != null) {
                            File f = new File(fullPath);
                            if (f.getParent() != null) parentPath = f.getParent();
                        }

                        groupedSongs.computeIfAbsent(parentPath, k -> new ArrayList<>()).add(song);
                        groupedBytes.put(parentPath, groupedBytes.getOrDefault(parentPath, 0L) + Math.max(0, fileSize));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            final long finalAudioBytes = audioBytesSum;
            handler.post(() -> {
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
        AuralisTelemetryManager.StorageSnapshot snap = AuralisTelemetryManager.getStorageSnapshot(this);
        allFolders.clear();
        for (Map.Entry<String, List<Song>> entry : folderSongsMap.entrySet()) {
            String path = entry.getKey();
            String name = new File(path).getName();
            if (name.isEmpty()) name = path;
            
            if (snap.hasSdCard && snap.sdCardRootPath != null && path.startsWith(snap.sdCardRootPath)) {
                name = "[MicroSD] " + name;
            } else if (path.startsWith("/storage/emulated/0") || !path.contains("whatsapp") ) {
                 name = "[Phone] " + name;
            }

            // Exclude whatsapp audio
            if (!path.toLowerCase().contains("whatsapp")) {
               long bytes = folderBytesMap.getOrDefault(path, 0L);
               allFolders.add(new FolderItem(name, path, entry.getValue().size(), bytes));
            }
        }

        if (sortByName) {
            Collections.sort(allFolders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        } else {
            Collections.sort(allFolders, (a, b) -> Long.compare(b.getTotalBytes(), a.getTotalBytes()));
        }

        renderCurrentDirectoryState();
    }

    private void renderCurrentDirectoryState() {
        if (selectedFolderPath == null) {
            // Root view: show all audio directories, and show all tracks below
            textBreadcrumbs.setText("All Storage  >  Audio Directories");
            if (textDirectoriesHeader != null) {
                textDirectoriesHeader.setText("DIRECTORIES (" + allFolders.size() + ")");
            }
            recyclerFolders.setVisibility(View.VISIBLE);
            recyclerFolders.setAdapter(new FolderAdapter(allFolders, folder -> {
                selectedFolderPath = folder.getPath();
                renderCurrentDirectoryState();
            }));

            List<Song> allTracksPreview = new ArrayList<>();
            for (List<Song> list : folderSongsMap.values()) {
                allTracksPreview.addAll(list);
            }
            if (textLooseFilesHeader != null) {
                textLooseFilesHeader.setText("ALL INDEXED FILES (" + allTracksPreview.size() + ")");
            }
            bindSongsList(allTracksPreview);
        } else {
            // Inside a specific folder
            String folderName = new File(selectedFolderPath).getName();
            textBreadcrumbs.setText("Storage  >  " + folderName);
            if (textDirectoriesHeader != null) {
                textDirectoriesHeader.setText("SUBDIRECTORIES (0)");
            }
            recyclerFolders.setVisibility(View.GONE);

            List<Song> folderSongs = folderSongsMap.getOrDefault(selectedFolderPath, new ArrayList<>());
            if (textLooseFilesHeader != null) {
                textLooseFilesHeader.setText("FILES IN " + folderName.toUpperCase() + " (" + folderSongs.size() + ")");
            }
            bindSongsList(folderSongs);
        }
    }

    private void bindSongsList(List<Song> songs) {
        SongAdapter adapter = new SongAdapter(songs, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(int position) {
                if (mediaController != null) {
                    mediaController.clearMediaItems();
                    for (Song s : songs) {
                        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                                .setTitle(s.getTitle())
                                .setArtist(s.getArtist());
                        if (s.getCustomCoverUri() != null) meta.setArtworkUri(s.getCustomCoverUri());
                        mediaController.addMediaItem(new MediaItem.Builder()
                                .setUri(s.getUri())
                                .setMediaMetadata(meta.build())
                                .build());
                    }
                    mediaController.seekToDefaultPosition(position);
                    mediaController.prepare();
                    mediaController.play();
                }
            }

            @Override
            public void onSongOptionsClick(int position) {
                Song s = songs.get(position);
                Toast.makeText(FoldersActivity.this, s.getTitle() + " (" + s.getArtist() + ")", Toast.LENGTH_SHORT).show();
            }
        });
        recyclerLooseFiles.setAdapter(adapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                mediaController.addListener(playerListener);
                syncMiniPlayerUI();
            } catch (ExecutionException | InterruptedException e) {
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