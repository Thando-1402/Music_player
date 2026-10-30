package com.thando1402.auralis;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaylistsActivity extends AppCompatActivity {

    public static final String PREFS_PLAYLISTS = "auralis_custom_playlists";
    public static final String KEY_JSON = "playlists_json";

    private final List<Song> allSongs = new ArrayList<>();
    private LinearLayout containerCustomPlaylists;
    private TextView tvFavCount;

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
        setContentView(R.layout.activity_playlists);

        scanExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        MaterialToolbar toolbar = findViewById(R.id.toolbar_playlists);
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_open_profile) {
                    startActivity(new Intent(this, ProfileSettingsActivity.class));
                    return true;
                }
                return false;
            });
        }

        containerCustomPlaylists = findViewById(R.id.container_custom_playlists);
        tvFavCount = findViewById(R.id.tv_fav_count);

        miniTitle = findViewById(R.id.mini_title);
        miniSubtitle = findViewById(R.id.mini_subtitle);
        btnPlayPause = findViewById(R.id.mini_play_pause);
        btnNext = findViewById(R.id.mini_next);
        miniArt = findViewById(R.id.mini_track_art);
        miniPlayerContainer = findViewById(R.id.mini_player_playlists);

        loadLocalTracksAsync();
        renderCustomPlaylists();

        View btnNewPlaylist = findViewById(R.id.btn_new_playlist);
        btnNewPlaylist.setOnClickListener(v -> showCreatePlaylistDialog());
        UiAnimationHelper.attachSpringTouch(btnNewPlaylist);

        findViewById(R.id.card_playlist_favorites).setOnClickListener(v -> {
            List<Song> favs = new ArrayList<>();
            for (Song s : allSongs) {
                if (SongMetadataManager.isFavorite(this, s.getUri().toString())) {
                    favs.add(s);
                }
            }
            if (favs.isEmpty()) {
                Toast.makeText(this, "Tap the ♥ icon in Now Playing to star tracks first!", Toast.LENGTH_SHORT).show();
            } else {
                playSongQueue(favs, "Playing Favourites Master (" + favs.size() + " tracks)");
            }
        });

        findViewById(R.id.card_playlist_ai_mix).setOnClickListener(v -> {
            if (allSongs.isEmpty()) return;
            List<Song> mix = OpenRouterAiHelper.curateSmartMix(this, allSongs);
            playSongQueue(mix, "✨ AI Smart Discovery Queue (" + mix.size() + " tracks)");
        });

        findViewById(R.id.card_playlist_chill).setOnClickListener(v -> {
            if (allSongs.isEmpty()) return;
            List<Song> chill = new ArrayList<>();
            for (Song s : allSongs) {
                if (s.getDuration() >= 210_000L) {
                    chill.add(s);
                }
            }
            if (chill.isEmpty()) chill.addAll(allSongs);
            playSongQueue(chill, "Playing Late-Night Sessions (" + chill.size() + " tracks)");
        });

        setupMiniPlayer();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_playlists);
        if (bottomNav != null) {
            NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_playlists);
        }
    }

    private void loadLocalTracksAsync() {
        scanExecutor.execute(() -> {
            List<Song> loaded = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] proj = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.VOLUME_NAME}
                    : new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION};

            int favCounter = 0;
            try (Cursor c = getContentResolver().query(collection, proj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.TITLE + " ASC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int tCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int aCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int dCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int volCol = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? c.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME) : -1;

                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String title = c.getString(tCol);
                        String artist = c.getString(aCol);
                        String data = c.getString(dCol);
                        long duration = c.getLong(durCol);

                        if (AudioFilterManager.shouldExcludeTrack(getApplicationContext(), title, data, duration)) {
                            continue;
                        }

                        Uri uri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volCol != -1) {
                            String vol = c.getString(volCol);
                            uri = (vol != null && !vol.isEmpty())
                                    ? ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(vol), id)
                                    : ContentUris.withAppendedId(collection, id);
                        } else {
                            uri = ContentUris.withAppendedId(collection, id);
                        }

                        Song s = new Song(title, artist, uri, duration);
                        SongMetadataManager.applySavedEdits(getApplicationContext(), s, false);
                        loaded.add(s);
                        if (SongMetadataManager.isFavorite(getApplicationContext(), uri.toString())) {
                            favCounter++;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            final int finalFavCount = favCounter;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                allSongs.clear();
                allSongs.addAll(loaded);
                if (tvFavCount != null) {
                    tvFavCount.setText(getResources().getQuantityString(
                            R.plurals.favourites_count_plural, finalFavCount, finalFavCount));
                }
            });
        });
    }

    private void showCreatePlaylistDialog() {
        if (allSongs.isEmpty()) {
            Toast.makeText(this, "No local tracks available", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText nameInput = new EditText(this);
        nameInput.setHint("Playlist Name (e.g., Roadtrip Hi-Res)");
        new AlertDialog.Builder(this)
                .setTitle("Create New Playlist")
                .setView(nameInput)
                .setPositiveButton("Select Tracks", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (!name.isEmpty()) showTrackPickerDialog(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTrackPickerDialog(String playlistName) {
        String[] labels = new String[allSongs.size()];
        boolean[] checked = new boolean[allSongs.size()];
        for (int i = 0; i < allSongs.size(); i++) {
            labels[i] = allSongs.get(i).getTitle() + " — " + allSongs.get(i).getArtist();
        }
        new AlertDialog.Builder(this)
                .setTitle("Add Tracks to " + playlistName)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save Playlist", (d, w) -> {
                    JSONArray uris = new JSONArray();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) uris.put(allSongs.get(i).getUri().toString());
                    }
                    saveCustomPlaylist(playlistName, uris);
                    renderCustomPlaylists();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCustomPlaylist(String name, JSONArray trackUris) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_PLAYLISTS, MODE_PRIVATE);
            JSONObject root = new JSONObject(prefs.getString(KEY_JSON, "{}"));
            root.put(name, trackUris);
            prefs.edit().putString(KEY_JSON, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void deleteCustomPlaylist(String name) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_PLAYLISTS, MODE_PRIVATE);
            JSONObject root = new JSONObject(prefs.getString(KEY_JSON, "{}"));
            root.remove(name);
            prefs.edit().putString(KEY_JSON, root.toString()).apply();
            renderCustomPlaylists();
        } catch (Exception ignored) {
        }
    }

    private void renderCustomPlaylists() {
        if (containerCustomPlaylists == null) return;
        containerCustomPlaylists.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_PLAYLISTS, MODE_PRIVATE);
            JSONObject root = new JSONObject(prefs.getString(KEY_JSON, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONArray arr = root.optJSONArray(name);
                int count = arr != null ? arr.length() : 0;

                View row = inflater.inflate(R.layout.item_playlist, containerCustomPlaylists, false);
                row.setBackgroundResource(R.drawable.bg_rounded_card_12dp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
                row.setLayoutParams(lp);

                TextView title = row.findViewById(R.id.text_playlist_name);
                TextView sub = row.findViewById(R.id.text_playlist_count);
                View btnOverflow = row.findViewById(R.id.btn_playlist_overflow);

                if (title != null) title.setText(name);
                if (sub != null) sub.setText(getString(R.string.playlist_custom_subtitle_fmt, count));

                row.setOnClickListener(v -> playCustomPlaylist(name, arr));
                row.setOnLongClickListener(v -> {
                    exportPlaylistM3u(name, arr);
                    return true;
                });
                if (btnOverflow != null) {
                    btnOverflow.setOnClickListener(v -> {
                        PopupMenu popup = new PopupMenu(this, v);
                        popup.getMenu().add(0, 1, 0, R.string.menu_play_now);
                        popup.getMenu().add(0, 2, 1, "Export .M3U");
                        popup.getMenu().add(0, 3, 2, "Delete Playlist");
                        popup.setOnMenuItemClickListener(item -> {
                            int id = item.getItemId();
                            if (id == 1) {
                                playCustomPlaylist(name, arr);
                                return true;
                            } else if (id == 2) {
                                exportPlaylistM3u(name, arr);
                                return true;
                            } else if (id == 3) {
                                deleteCustomPlaylist(name);
                                return true;
                            }
                            return false;
                        });
                        popup.show();
                    });
                }
                containerCustomPlaylists.addView(row);
            }
        } catch (Exception ignored) {
        }
    }

    private void playCustomPlaylist(String name, JSONArray uriArray) {
        if (uriArray == null || uriArray.length() == 0) return;
        List<Song> queue = new ArrayList<>();
        for (int i = 0; i < uriArray.length(); i++) {
            String u = uriArray.optString(i, "");
            for (Song s : allSongs) {
                if (s.getUri().toString().equals(u)) {
                    queue.add(s);
                    break;
                }
            }
        }
        if (!queue.isEmpty()) {
            playSongQueue(queue, "Playing \"" + name + "\"");
        }
    }

    private void exportPlaylistM3u(String name, JSONArray uriArray) {
        try {
            File dir = new File(SongMetadataManager.getStorageDirectory(this), "playlists");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File m3u = new File(dir, name.replaceAll("[^a-zA-Z0-9_-]", "_") + ".m3u");
            StringBuilder sb = new StringBuilder("#EXTM3U\n");
            if (uriArray != null) {
                for (int i = 0; i < uriArray.length(); i++) {
                    sb.append(uriArray.optString(i)).append("\n");
                }
            }
            try (FileOutputStream fos = new FileOutputStream(m3u)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Exported: " + m3u.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void playSongQueue(List<Song> songs, String toastMessage) {
        if (mediaController == null || songs.isEmpty()) return;
        List<MediaItem> items = PlaybackStateHelper.buildAdaptiveQueue(songs);
        mediaController.setMediaItems(items, 0, 0);
        mediaController.prepare();
        mediaController.play();
        syncMiniPlayerUI();
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
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
            if (future != controllerFuture || future.isCancelled() || isFinishing() || isDestroyed()) return;
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
