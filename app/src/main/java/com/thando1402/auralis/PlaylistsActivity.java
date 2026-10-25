package com.thando1402.auralis;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class PlaylistsActivity extends AppCompatActivity implements PlaylistAdapter.OnPlaylistClickListener {

    private static final String PREFS_NAME = "auralis_playlists_prefs";
    private static final String KEY_CUSTOM_PLAYLISTS = "custom_playlists";

    private RecyclerView recyclerPlaylists;
    private PlaylistAdapter adapter;
    private final List<Playlist> allPlaylists = new ArrayList<>();
    private final List<Playlist> displayedPlaylists = new ArrayList<>();

    private Chip chipAll, chipFavorites, chipRecentlyAdded;

    // Mini Player
    private View miniPlayerContainer;
    private TextView miniTitle, miniSubtitle;
    private AppCompatImageButton btnMiniPrev, btnMiniPlayPause, btnMiniNext;
    private ShapeableImageView miniArt;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

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
            if (btnMiniPlayPause != null) {
                btnMiniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlists);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav_playlists);
        NavigationHelper.setupBottomNavigation(this, bottomNav, R.id.nav_playlists);

        recyclerPlaylists = findViewById(R.id.recycler_playlists);
        recyclerPlaylists.setLayoutManager(new LinearLayoutManager(this));
        recyclerPlaylists.setHasFixedSize(true);

        adapter = new PlaylistAdapter(displayedPlaylists, this);
        recyclerPlaylists.setAdapter(adapter);

        setupChips();
        setupSmartCurator();
        setupNewPlaylistButton();
        setupMiniPlayer();

        loadPlaylists();
    }

    private void setupChips() {
        chipAll = findViewById(R.id.chip_all_playlists);
        chipFavorites = findViewById(R.id.chip_favorites);
        chipRecentlyAdded = findViewById(R.id.chip_recently_added);

        if (chipAll != null) {
            chipAll.setOnClickListener(v -> filterPlaylists("all"));
        }
        if (chipFavorites != null) {
            chipFavorites.setOnClickListener(v -> filterPlaylists("favorites"));
        }
        if (chipRecentlyAdded != null) {
            chipRecentlyAdded.setOnClickListener(v -> filterPlaylists("recent"));
        }
    }

    private void filterPlaylists(String filterType) {
        displayedPlaylists.clear();
        if ("all".equals(filterType)) {
            displayedPlaylists.addAll(allPlaylists);
        } else {
            for (Playlist p : allPlaylists) {
                if (p.getType().equals(filterType)) {
                    displayedPlaylists.add(p);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void setupSmartCurator() {
        View curatorCard = findViewById(R.id.card_smart_curator);
        View curatorAction = findViewById(R.id.btn_smart_curator_action);

        View.OnClickListener listener = v -> {
            Toast.makeText(this, "✨ AI Smart Curator generated a dynamic high-energy mix!", Toast.LENGTH_SHORT).show();
            // Ensure Smart Mix is in the playlist
            boolean exists = false;
            for (Playlist p : allPlaylists) {
                if ("smart_curator".equals(p.getId())) {
                    p.setTrackCount(p.getTrackCount() + 5);
                    p.setSubtitle(p.getTrackCount() + " tracks • AI Generated");
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                allPlaylists.add(1, new Playlist("smart_curator", "✨ AI Smart Mix", "18 tracks • AI Generated", 18, R.drawable.ic_auto_awesome_24, "smart"));
            }
            filterPlaylists("all");
        };

        if (curatorCard != null) curatorCard.setOnClickListener(listener);
        if (curatorAction != null) curatorAction.setOnClickListener(listener);
    }

    private void setupNewPlaylistButton() {
        View btnNew = findViewById(R.id.btn_new_playlist);
        if (btnNew != null) {
            btnNew.setOnClickListener(v -> showCreatePlaylistDialog());
        }
    }

    private void showCreatePlaylistDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Playlist");

        final EditText input = new EditText(this);
        input.setHint("e.g. Focus & Study, Workout");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                createCustomPlaylist(name);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void createCustomPlaylist(String name) {
        String id = "custom_" + System.currentTimeMillis();
        Playlist newPlaylist = new Playlist(id, name, "0 tracks • Offline", 0, R.drawable.ic_queue_music_24, "custom");
        allPlaylists.add(newPlaylist);
        saveCustomPlaylists();
        filterPlaylists("all");
        Toast.makeText(this, "Playlist \"" + name + "\" created", Toast.LENGTH_SHORT).show();
    }

    private void loadPlaylists() {
        allPlaylists.clear();

        // 1. Default system playlists
        allPlaylists.add(new Playlist("favorites", "Favorites & Starred", "Offline • Instant Access", 0, R.drawable.ic_favorite_24, "favorites"));
        allPlaylists.add(new Playlist("smart_curator", "✨ AI Smart Mix", "Dynamic Mix • AI Curation", 15, R.drawable.ic_auto_awesome_24, "smart"));
        allPlaylists.add(new Playlist("recent", "Recently Added Tracks", "Added this week", 8, R.drawable.ic_library_music_24, "recent"));

        // 2. Load custom playlists from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedJson = prefs.getString(KEY_CUSTOM_PLAYLISTS, "");
        if (!savedJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(savedJson);
                for (int i = 0; i < arr.length(); i++) {
                    String title = arr.getString(i);
                    allPlaylists.add(new Playlist("custom_" + i, title, "Custom • Offline", 0, R.drawable.ic_queue_music_24, "custom"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        displayedPlaylists.clear();
        displayedPlaylists.addAll(allPlaylists);
        adapter.notifyDataSetChanged();
    }

    private void saveCustomPlaylists() {
        JSONArray arr = new JSONArray();
        for (Playlist p : allPlaylists) {
            if ("custom".equals(p.getType())) {
                arr.put(p.getTitle());
            }
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_PLAYLISTS, arr.toString())
                .apply();
    }

    @Override
    public void onPlaylistClick(Playlist playlist) {
        Toast.makeText(this, "Opening \"" + playlist.getTitle() + "\"", Toast.LENGTH_SHORT).show();
        // Return to MainActivity with filter or start playback
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }

    @Override
    public void onPlaylistOptionsClick(Playlist playlist, View anchor) {
        if ("custom".equals(playlist.getType())) {
            new AlertDialog.Builder(this)
                    .setTitle(playlist.getTitle())
                    .setItems(new CharSequence[]{"Play All", "Rename", "Delete Playlist"}, (dialog, which) -> {
                        if (which == 0) {
                            onPlaylistClick(playlist);
                        } else if (which == 1) {
                            showRenameDialog(playlist);
                        } else if (which == 2) {
                            allPlaylists.remove(playlist);
                            saveCustomPlaylists();
                            filterPlaylists("all");
                            Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } else {
            Toast.makeText(this, playlist.getTitle(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameDialog(Playlist playlist) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Playlist");
        final EditText input = new EditText(this);
        input.setText(playlist.getTitle());
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                playlist.setTitle(newName);
                saveCustomPlaylists();
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // Mini Player integration
    private void setupMiniPlayer() {
        miniPlayerContainer = findViewById(R.id.mini_player_playlists);
        if (miniPlayerContainer == null) return;

        miniTitle = miniPlayerContainer.findViewById(R.id.mini_title);
        miniSubtitle = miniPlayerContainer.findViewById(R.id.mini_subtitle);
        btnMiniPrev = miniPlayerContainer.findViewById(R.id.mini_prev);
        btnMiniPlayPause = miniPlayerContainer.findViewById(R.id.mini_play_pause);
        btnMiniNext = miniPlayerContainer.findViewById(R.id.mini_next);
        miniArt = miniPlayerContainer.findViewById(R.id.mini_track_art);

        miniPlayerContainer.setOnClickListener(v -> {
            startActivity(new Intent(this, NowPlayingActivity.class));
            overridePendingTransition(R.anim.slide_up_in, 0);
        });

        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setOnClickListener(v -> {
                if (mediaController != null) {
                    if (mediaController.isPlaying()) mediaController.pause();
                    else mediaController.play();
                }
            });
        }

        if (btnMiniPrev != null) {
            btnMiniPrev.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToPrevious();
            });
        }

        if (btnMiniNext != null) {
            btnMiniNext.setOnClickListener(v -> {
                if (mediaController != null) mediaController.seekToNext();
            });
        }
    }

    private void syncMiniPlayerUI() {
        if (miniPlayerContainer == null) return;
        if (mediaController == null || mediaController.getCurrentMediaItem() == null) {
            miniPlayerContainer.setVisibility(View.GONE);
            return;
        }

        miniPlayerContainer.setVisibility(View.VISIBLE);
        MediaMetadata metadata = mediaController.getMediaMetadata();

        CharSequence title = metadata.title;
        CharSequence artist = metadata.artist;

        if (miniTitle != null) {
            miniTitle.setText(title != null && title.length() > 0 ? title : "Auralis Audio");
        }
        if (miniSubtitle != null) {
            miniSubtitle.setText(artist != null && artist.length() > 0 ? artist : "Local Library");
        }
        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setImageResource(mediaController.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                if (!controllerFuture.isCancelled()) {
                    mediaController = controllerFuture.get();
                    if (mediaController != null) {
                        mediaController.addListener(playerListener);
                        syncMiniPlayerUI();
                    }
                }
            } catch (ExecutionException | InterruptedException | CancellationException ignored) {
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        if (mediaController != null) {
            mediaController.removeListener(playerListener);
            mediaController = null;
        }
        super.onStop();
    }
}