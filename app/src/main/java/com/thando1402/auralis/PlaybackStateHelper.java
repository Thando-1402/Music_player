package com.thando1402.auralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.palette.graphics.Palette;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlaybackStateHelper {

    private static final String PREFS_LAST_PLAYED = "auralis_last_played";
    private static final String KEY_URI = "last_uri";
    private static final String KEY_TITLE = "last_title";
    private static final String KEY_ARTIST = "last_artist";
    private static final String KEY_COVER = "last_cover";
    private static final String KEY_POSITION_MS = "last_position_ms";
    private static final String KEY_AUTO_RESUME = "auto_restore_last_track";

    public static MediaItem buildAdaptiveMediaItem(Song song) {
        Uri artworkUri = song.getCustomCoverUri() != null
                ? song.getCustomCoverUri()
                : Uri.withAppendedPath(song.getUri(), "albumart");

        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(song.getTitle())
                .setArtist(song.getArtist())
                .setArtworkUri(artworkUri)
                .build();

        return new MediaItem.Builder()
                .setUri(song.getUri())
                .setMediaId(song.getUri().toString())
                .setMediaMetadata(meta)
                .build();
    }

    public static List<MediaItem> buildAdaptiveQueue(List<Song> songs) {
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (Song s : songs) {
            items.add(buildAdaptiveMediaItem(s));
        }
        return items;
    }

    /**
     * Ensures any extracted accent color is visible against dark surfaces and never pure black/white.
     */
    public static int ensureReadableAccent(int rawColor, int fallbackColor) {
        double lum = ColorUtils.calculateLuminance(rawColor);
        if (lum < 0.12) {
            // Too dark (e.g., black album cover) -> blend toward electric cyan/white
            return ColorUtils.blendARGB(rawColor, fallbackColor, 0.65f);
        } else if (lum > 0.85) {
            // Very bright white cover -> slightly tone down so it doesn't wash out
            return ColorUtils.blendARGB(rawColor, fallbackColor, 0.30f);
        }
        return rawColor;
    }

    public static void saveLastPlayedState(Context context, MediaController controller) {
        if (context == null || controller == null) return;
        MediaItem current = controller.getCurrentMediaItem();
        if (current == null || current.mediaId.isEmpty()) return;

        String uriStr = current.mediaId;
        String title = current.mediaMetadata.title != null ? current.mediaMetadata.title.toString() : "Unknown Track";
        String artist = current.mediaMetadata.artist != null ? current.mediaMetadata.artist.toString() : "Unknown Artist";
        String coverStr = current.mediaMetadata.artworkUri != null ? current.mediaMetadata.artworkUri.toString() : "";
        long posMs = Math.max(0L, controller.getCurrentPosition());

        SharedPreferences prefs = context.getSharedPreferences(PREFS_LAST_PLAYED, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_URI, uriStr)
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .putString(KEY_COVER, coverStr)
                .putLong(KEY_POSITION_MS, posMs)
                .apply();

        if (isOnline(context)) {
            try {
                Bundle payload = new Bundle();
                payload.putString("track_uri", uriStr);
                payload.putString("track_title", title);
                payload.putString("track_artist", artist);
                payload.putLong("position_ms", posMs);
                FirebaseAnalytics.getInstance(context.getApplicationContext())
                        .logEvent("auralis_last_played_sync", payload);
            } catch (Exception ignored) {
            }
        }
    }

    public static void restoreLastPlayedIfIdle(Context context, MediaController controller) {
        if (context == null || controller == null) return;
        if (controller.getMediaItemCount() > 0) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_LAST_PLAYED, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_AUTO_RESUME, true)) return;

        String uriStr = prefs.getString(KEY_URI, "");
        if (uriStr.isEmpty()) return;

        try {
            Uri songUri = Uri.parse(uriStr);
            // Verify file actually exists in MediaStore before restoring ghost tracks
            try (android.database.Cursor c = context.getContentResolver().query(songUri, new String[]{android.provider.MediaStore.Audio.Media._ID}, null, null, null)) {
                if (c == null || !c.moveToFirst()) {
                    prefs.edit().clear().apply();
                    return;
                }
            } catch (Exception e) {
                prefs.edit().clear().apply();
                return;
            }

            String title = prefs.getString(KEY_TITLE, "Unknown Track");
            String artist = prefs.getString(KEY_ARTIST, "Unknown Artist");
            String coverStr = prefs.getString(KEY_COVER, "");
            long posMs = prefs.getLong(KEY_POSITION_MS, 0L);

            Uri artUri = !coverStr.isEmpty() ? Uri.parse(coverStr) : Uri.withAppendedPath(songUri, "albumart");
            MediaMetadata meta = new MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artUri)
                    .build();
            MediaItem item = new MediaItem.Builder()
                    .setUri(songUri)
                    .setMediaId(uriStr)
                    .setMediaMetadata(meta)
                    .build();
            controller.setMediaItems(Collections.singletonList(item), 0, posMs);
            controller.prepare();
            controller.pause();
        } catch (Exception ignored) {
        }
    }

    public static void syncMiniPlayerView(
            Context context,
            MediaController controller,
            View miniContainer,
            TextView miniTitle,
            TextView miniSubtitle,
            ShapeableImageView miniArt,
            ImageButton btnPlayPause) {
        if (controller == null) return;
        restoreLastPlayedIfIdle(context, controller);

        MediaItem item = controller.getCurrentMediaItem();
        if (item == null || item.mediaId.isEmpty()) {
            if (miniContainer != null) miniContainer.setVisibility(View.GONE);
            return;
        }
        if (miniContainer != null) miniContainer.setVisibility(View.VISIBLE);

        CharSequence title = item.mediaMetadata.title;
        CharSequence artist = item.mediaMetadata.artist;
        if (miniTitle != null && title != null && title.length() > 0) miniTitle.setText(title);
        if (miniSubtitle != null && artist != null && artist.length() > 0) miniSubtitle.setText(artist);

        if (miniArt != null) {
            if (item.mediaMetadata.artworkUri != null) {
                try {
                    miniArt.setImageURI(item.mediaMetadata.artworkUri);
                    if (miniArt.getDrawable() == null) {
                        miniArt.setImageResource(R.drawable.ic_album_placeholder);
                        resetMiniPlayerPalette(context, miniContainer, miniTitle, miniSubtitle, btnPlayPause);
                    } else {
                        applyPaletteToMiniPlayer(context, item.mediaMetadata.artworkUri, miniContainer, miniTitle, miniSubtitle, btnPlayPause);
                    }
                } catch (Exception e) {
                    miniArt.setImageResource(R.drawable.ic_album_placeholder);
                    resetMiniPlayerPalette(context, miniContainer, miniTitle, miniSubtitle, btnPlayPause);
                }
            } else {
                miniArt.setImageResource(R.drawable.ic_album_placeholder);
                resetMiniPlayerPalette(context, miniContainer, miniTitle, miniSubtitle, btnPlayPause);
            }
        }
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(controller.isPlaying() ? R.drawable.ic_pause_24 : R.drawable.ic_play_arrow_24);
        }
        if (miniContainer != null) {
             ImageButton btnClose = miniContainer.findViewById(R.id.btn_close_mini);
             if (btnClose != null) {
                 btnClose.setOnClickListener(v -> {
                     if (controller.isPlaying()) controller.pause();
                     controller.clearMediaItems();
                     miniContainer.setVisibility(View.GONE);
                 });
             }
        }
    }

    private static void applyPaletteToMiniPlayer(
            Context context,
            Uri artUri,
            View container,
            TextView title,
            TextView subtitle,
            ImageButton btnPlayPause) {
        if (container == null) return;
        try (InputStream is = context.getContentResolver().openInputStream(artUri)) {
            if (is == null) return;
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                Palette.from(bitmap).generate(palette -> {
                    if (palette == null) return;
                    int defaultCard = ContextCompat.getColor(context, R.color.surface_card);
                    int defaultAccent = ContextCompat.getColor(context, R.color.accent_blue);

                    int rawDom = palette.getDominantColor(defaultCard);
                    int rawVibrant = palette.getVibrantColor( palette.getLightVibrantColor(rawDom));
                    int safeAccent = ensureReadableAccent(rawVibrant, defaultAccent);

                    // Create a smooth horizontal gradient fade from the album's dark tint into #16181D
                    // so the right-side playback buttons and text NEVER blend into bright or dark covers!
                    int startBg = ColorUtils.blendARGB(rawDom, Color.parseColor("#121316"), 0.55f);
                    int endBg = Color.parseColor("#181A20");
                    GradientDrawable gd = new GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            new int[]{startBg, endBg});
                    container.setBackground(gd);

                    boolean isLightBg = ColorUtils.calculateLuminance(startBg) > 0.45;
                    int primaryText = isLightBg ? Color.parseColor("#121316") : Color.WHITE;
                    int secondaryText = isLightBg ? Color.parseColor("#44474E") : Color.parseColor("#B0B5BE");

                    if (title != null) title.setTextColor(primaryText);
                    if (subtitle != null) subtitle.setTextColor(secondaryText);
                    if (btnPlayPause != null) {
                        btnPlayPause.setImageTintList(ColorStateList.valueOf(safeAccent));
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private static void resetMiniPlayerPalette(
            Context context,
            View container,
            TextView title,
            TextView subtitle,
            ImageButton btnPlayPause) {
        if (container != null) {
            container.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_card));
        }
        if (title != null) title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        if (subtitle != null) subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        if (btnPlayPause != null) {
            btnPlayPause.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_blue)));
        }
    }

    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    public static AudioDeviceCallback registerLiveOutputTracker(Context context, TextView tvDeviceName, TextView tvCodec) {
        updateOutputLabels(context, tvDeviceName, tvCodec);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        AudioDeviceCallback callback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                updateOutputLabels(context, tvDeviceName, tvCodec);
            }
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                updateOutputLabels(context, tvDeviceName, tvCodec);
            }
        };
        am.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
        return callback;
    }

    public static void unregisterLiveOutputTracker(Context context, AudioDeviceCallback callback) {
        if (context == null || callback == null) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.unregisterAudioDeviceCallback(callback);
    }

    public static void updateOutputLabels(Context context, TextView tvDeviceName, TextView tvCodec) {
        if (tvDeviceName == null || tvCodec == null) return;
        String deviceTitle = "Internal Hi-Fi Speaker DAC";
        String codecLabel = "24-BIT PCM • SPEAKER";
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int type = d.getType();
                    String prod = d.getProductName() != null ? d.getProductName().toString().trim() : "";
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                        deviceTitle = "3.5mm Wired Headphones";
                        codecLabel = "ANALOG 24-BIT / 96KHZ";
                        break;
                    } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        deviceTitle = !prod.isEmpty() ? prod : "Bluetooth Audio";
                        codecLabel = "BLUETOOTH A2DP / LDAC";
                        break;
                    } else if (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                        deviceTitle = !prod.isEmpty() ? prod : "External USB DAC";
                        codecLabel = "USB BIT-PERFECT DIRECT";
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        tvDeviceName.setText(deviceTitle);
        tvCodec.setText(codecLabel);
    }
}