package com.thando1402.auralis;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaNotification;

public class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession mediaSession;
    private static volatile int activeAudioSessionId = 0;

    public static int getActiveAudioSessionId() {
        return activeAudioSessionId;
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 1. Create a Notification Channel (Required for Android 8+)
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

        // 2. Initialize the Player
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        activeAudioSessionId = player.getAudioSessionId();
        player.addListener(new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                activeAudioSessionId = audioSessionId;
            }
        });

        // 3. Configure the Custom Notification Provider
        DefaultMediaNotificationProvider notificationProvider = new DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(1)
                .setChannelId("auralis_playback_channel")
                .build();
        
        // Optional: Customize small icon
        notificationProvider.setSmallIcon(R.drawable.ic_library_music_24); 

        // 4. Build the MediaSession and set the provider
        mediaSession = new MediaSession.Builder(this, player).build();
        setMediaNotificationProvider(notificationProvider);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}