package com.thando1402.auralis;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends MediaSessionService {

    private static volatile int activeAudioSessionId = 0;

    private ExoPlayer player;
    private MediaSession mediaSession;

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;
    private int boundEffectSessionId = 0;
    private SharedPreferences eqPrefs;

    private ExecutorService artworkExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> enrichedMediaIds = Collections.synchronizedSet(new HashSet<>());

    private final SharedPreferences.OnSharedPreferenceChangeListener dspPrefListener =
            (sharedPreferences, key) -> applySavedDspSettings();

    public static int getActiveAudioSessionId() {
        return activeAudioSessionId;
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();
        artworkExecutor = Executors.newSingleThreadExecutor();
        eqPrefs = getSharedPreferences(OpenRouterAiHelper.PREFS_NAME, Context.MODE_PRIVATE);
        eqPrefs.registerOnSharedPreferenceChangeListener(dspPrefListener);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "auralis_playback_channel",
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ongoing music playback controls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // Explicit Music AudioAttributes so Android automatically ducks on notifications and pauses on phone calls
        AudioAttributes musicAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(musicAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        activeAudioSessionId = player.getAudioSessionId();
        attachAudioEffects(activeAudioSessionId);

        player.addListener(new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                activeAudioSessionId = audioSessionId;
                attachAudioEffects(audioSessionId);
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null) {
                    enrichMediaItemWithArtworkBytes(mediaItem);
                }
            }
        });

        DefaultMediaNotificationProvider notificationProvider = new DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(1)
                .setChannelId("auralis_playback_channel")
                .build();
        notificationProvider.setSmallIcon(R.drawable.ic_library_music_24);

        Intent openPlayerIntent = new Intent(this, NowPlayingActivity.class);
        PendingIntent sessionActivity = PendingIntent.getActivity(
                this,
                0,
                openPlayerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .build();
        setMediaNotificationProvider(notificationProvider);
    }

    @SuppressWarnings("deprecation")
    private void attachAudioEffects(int sessionId) {
        if (sessionId <= 0 || sessionId == boundEffectSessionId) return;
        boundEffectSessionId = sessionId;
        releaseAudioEffects();
        try {
            equalizer = new Equalizer(0, sessionId);
            bassBoost = new BassBoost(0, sessionId);
            virtualizer = new Virtualizer(0, sessionId);
            applySavedDspSettings();
        } catch (Exception e) {
            AuralisTelemetryManager.recordNonFatalError(this, "DSP_INIT", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void applySavedDspSettings() {
        if (eqPrefs == null) return;
        try {
            boolean enabled = eqPrefs.getBoolean("eq_master_enabled", true);
            int[] defaults = {70, 60, 50, 65, 75};

            if (equalizer != null) {
                equalizer.setEnabled(enabled);
                short bands = equalizer.getNumberOfBands();
                short[] range = equalizer.getBandLevelRange();
                short minMb = range[0];
                short maxMb = range[1];
                for (short i = 0; i < Math.min(5, bands); i++) {
                    int progress = eqPrefs.getInt("eq_band_" + i, defaults[i]);
                    short targetMb = (short) (minMb + ((maxMb - minMb) * progress) / 100);
                    equalizer.setBandLevel(i, targetMb);
                }
            }
            if (bassBoost != null) {
                bassBoost.setEnabled(enabled);
                if (bassBoost.getStrengthSupported()) {
                    int bassPct = eqPrefs.getInt("dsp_bass_pct", 65);
                    short strength = (short) Math.max(0, Math.min(1000, bassPct * 10));
                    bassBoost.setStrength(strength);
                }
            }
            if (virtualizer != null) {
                virtualizer.setEnabled(enabled);
                if (virtualizer.getStrengthSupported()) {
                    int virtPct = eqPrefs.getInt("dsp_virt_pct", 40);
                    short strength = (short) Math.max(0, Math.min(1000, virtPct * 10));
                    virtualizer.setStrength(strength);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Injects compressed cover art bytes + saved custom metadata into MediaMetadata so Android's
     * system media notification & lockscreen always display cover art and dynamic notification tint.
     */
    private void enrichMediaItemWithArtworkBytes(@NonNull MediaItem item) {
        if (item.mediaId.isEmpty() || item.mediaMetadata.artworkData != null) return;
        if (!enrichedMediaIds.add(item.mediaId)) return;

        final String targetId = item.mediaId;
        final Uri artUri = item.mediaMetadata.artworkUri;
        final Context appCtx = getApplicationContext();

        artworkExecutor.execute(() -> {
            Uri audioUri = Uri.parse(targetId);
            String curTitle = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "Unknown Track";
            String curArtist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString() : "Unknown Artist";

            Song temp = new Song(curTitle, curArtist, audioUri, 0);
            SongMetadataManager.applySavedEdits(appCtx, temp, false);
            Uri effectiveArtUri = temp.getCustomCoverUri() != null ? temp.getCustomCoverUri() : artUri;

            Bitmap bmp = ArtworkColorHelper.loadArtworkBitmap(appCtx, effectiveArtUri, audioUri, 320);
            byte[] bytes = ArtworkColorHelper.compressToBytes(bmp);
            if (bytes == null || bytes.length == 0) return;

            mainHandler.post(() -> {
                if (player == null) return;
                int count = player.getMediaItemCount();
                for (int i = 0; i < count; i++) {
                    MediaItem existing = player.getMediaItemAt(i);
                    if (targetId.equals(existing.mediaId)) {
                        MediaMetadata updatedMeta = existing.mediaMetadata.buildUpon()
                                .setTitle(temp.getTitle())
                                .setArtist(temp.getArtist())
                                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build();
                        MediaItem updatedItem = existing.buildUpon()
                                .setMediaMetadata(updatedMeta)
                                .build();
                        player.replaceMediaItem(i, updatedItem);
                        break;
                    }
                }
            });
        });
    }

    private void releaseAudioEffects() {
        try {
            if (equalizer != null) {
                equalizer.release();
                equalizer = null;
            }
            if (bassBoost != null) {
                bassBoost.release();
                bassBoost = null;
            }
            if (virtualizer != null) {
                virtualizer.release();
                virtualizer = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        activeAudioSessionId = 0;
        boundEffectSessionId = 0;
        if (eqPrefs != null) {
            eqPrefs.unregisterOnSharedPreferenceChangeListener(dspPrefListener);
        }
        releaseAudioEffects();
        if (artworkExecutor != null) {
            artworkExecutor.shutdownNow();
        }
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        } else if (player != null) {
            player.release();
        }
        player = null;
        super.onDestroy();
    }
}
