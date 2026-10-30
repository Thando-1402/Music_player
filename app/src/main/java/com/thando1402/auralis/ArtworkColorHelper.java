package com.thando1402.auralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class ArtworkColorHelper {

    private static final LruCache<String, Integer> ACCENT_CACHE = new LruCache<>(80);

    // Bounded 2.5 MB RGB_565 bitmap cache so Nokia 1.4 never re-decodes cover art or OOMs
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<>(2560 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value != null ? value.getAllocationByteCount() : 1;
        }
    };

    // Negative cache so tracks without cover art never invoke native MediaMetadataRetriever again
    private static final Set<String> NO_ARTWORK_CACHE = Collections.synchronizedSet(new HashSet<>());

    private ArtworkColorHelper() {}

    public static void invalidateCacheForSong(@Nullable String songUriKey) {
        if (songUriKey == null || songUriKey.isEmpty()) return;
        NO_ARTWORK_CACHE.remove(songUriKey);
        ACCENT_CACHE.remove(songUriKey);
        for (String k : BITMAP_CACHE.snapshot().keySet()) {
            if (k.contains(songUriKey)) {
                BITMAP_CACHE.remove(k);
            }
        }
        for (String k : ACCENT_CACHE.snapshot().keySet()) {
            if (k.contains(songUriKey)) {
                ACCENT_CACHE.remove(k);
            }
        }
    }

    public static void invalidateCache(@Nullable String cacheKey) {
        invalidateCacheForSong(cacheKey);
    }

    @Nullable
    public static Bitmap getCachedThumbnail(@Nullable String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) return null;
        Bitmap cached = BITMAP_CACHE.get(cacheKey);
        return (cached != null && !cached.isRecycled()) ? cached : null;
    }

    @Nullable
    public static Bitmap loadThumbnailCached(Context context, @Nullable String cacheKey, @Nullable Uri artworkUri, @Nullable Uri audioUri, int reqSize) {
        Bitmap existing = getCachedThumbnail(cacheKey);
        if (existing != null) return existing;
        Bitmap loaded = loadArtworkBitmap(context, artworkUri, audioUri, reqSize);
        if (loaded != null && cacheKey != null && !cacheKey.isEmpty()) {
            BITMAP_CACHE.put(cacheKey, loaded);
        }
        return loaded;
    }

    /**
     * Loads album art without spamming native `MediaMetadataRetrieverJNI: getEmbeddedPicture failed`:
     * 1. Checks `BITMAP_CACHE` first.
     * 2. Decodes explicit custom `file://` cover if set.
     * 3. Checks `NO_ARTWORK_CACHE` to skip tracks already verified to lack artwork.
     * 4. Queries ContentResolver / MediaStore `albumart` thumbnails (zero JNI error logs).
     * 5. Pre-checks container header for embedded picture markers (`APIC`/`PIC`/`covr`/FLAC picture)
     *    and `METADATA_KEY_HAS_IMAGE` before invoking `getEmbeddedPicture()` at most once per song.
     */
    @Nullable
    public static Bitmap loadArtworkBitmap(Context context, @Nullable Uri artworkUri, @Nullable Uri audioUri, int reqSize) {
        if (context == null) return null;

        String baseKey = audioUri != null ? audioUri.toString() : (artworkUri != null ? artworkUri.toString() : null);
        String sizeKey = baseKey != null
                ? (baseKey + (artworkUri != null && "file".equalsIgnoreCase(artworkUri.getScheme()) ? "|" + artworkUri : "") + "_" + reqSize)
                : null;

        if (sizeKey != null) {
            Bitmap cachedBmp = BITMAP_CACHE.get(sizeKey);
            if (cachedBmp != null && !cachedBmp.isRecycled()) {
                return cachedBmp;
            }
        }

        // Custom file cover explicitly set by user
        if (artworkUri != null && "file".equalsIgnoreCase(artworkUri.getScheme())) {
            Bitmap customBmp = decodeUri(context, artworkUri, reqSize);
            if (customBmp != null) {
                BITMAP_CACHE.put(sizeKey, customBmp);
                NO_ARTWORK_CACHE.remove(baseKey);
                return customBmp;
            }
        }

        // Skip native JNI calls if this track was already verified to have no artwork
        if (baseKey != null && NO_ARTWORK_CACHE.contains(baseKey)) {
            return null;
        }

        // 1. Fast ContentResolver / MediaStore albumart lookup (Zero JNI error logs)
        Bitmap bmp = decodeUri(context, artworkUri, reqSize);
        if (bmp == null && audioUri != null) {
            try {
                Uri mediaStoreArtUri = Uri.withAppendedPath(audioUri, "albumart");
                if (!Objects.equals(mediaStoreArtUri, artworkUri)) {
                    bmp = decodeUri(context, mediaStoreArtUri, reqSize);
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Quiet fallback to MediaMetadataRetriever at most once per song
        if (bmp == null && audioUri != null) {
            bmp = extractEmbeddedPictureQuietly(context, audioUri, reqSize);
        }

        if (bmp != null) {
            if (sizeKey != null) BITMAP_CACHE.put(sizeKey, bmp);
        } else if (baseKey != null) {
            NO_ARTWORK_CACHE.add(baseKey);
        }
        return bmp;
    }

    @Nullable
    private static Bitmap decodeUri(Context context, @Nullable Uri uri, int reqSize) {
        if (uri == null) return null;

        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists()) {
                return decodeSampledFile(f.getAbsolutePath(), reqSize);
            }
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equalsIgnoreCase(uri.getScheme())) {
            try {
                return context.getContentResolver().loadThumbnail(uri, new Size(reqSize, reqSize), null);
            } catch (Exception ignored) {
                // On Android 10+, MediaProvider throws FileNotFoundException when no thumbnail exists;
                // return null immediately for /albumart URIs rather than opening a broken stream
                if (uri.getPath() != null && uri.getPath().endsWith("/albumart")) {
                    return null;
                }
            }
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, reqSize);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream is2 = context.getContentResolver().openInputStream(uri)) {
                if (is2 == null) return null;
                return BitmapFactory.decodeStream(is2, null, opts);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeSampledFile(String path, int reqSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, reqSize);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pre-scans the audio container header so MP3/WAV files without embedded picture frames never
     * call `mmr.getEmbeddedPicture()` (preventing native `MediaMetadataRetrieverJNI` error spam).
     */
    private static boolean mayContainEmbeddedPicture(Context context, Uri audioUri) {
        try (InputStream is = context.getContentResolver().openInputStream(audioUri)) {
            if (is == null) return false;
            byte[] buf = new byte[65536];
            int total = 0;
            while (total < buf.length) {
                int r = is.read(buf, total, buf.length - total);
                if (r <= 0) break;
                total += r;
            }
            if (total < 12) return false;

            // Raw MP3 frame sync with no ID3v2 header -> cannot contain ID3v2 APIC cover art
            if ((buf[0] & 0xFF) == 0xFF && (buf[1] & 0xE0) == 0xE0) {
                return false;
            }
            // RIFF/WAV without ID3 chunk -> rarely has embedded art unless "APIC" present
            if (buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F') {
                String ascii = new String(buf, 0, total, StandardCharsets.ISO_8859_1);
                return ascii.contains("APIC");
            }
            // ID3v2 header -> check if APIC (v2.3/v2.4) or PIC (v2.2) appears in the scanned header
            if (buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') {
                int tagSize = ((buf[6] & 0x7F) << 21)
                        | ((buf[7] & 0x7F) << 14)
                        | ((buf[8] & 0x7F) << 7)
                        | (buf[9] & 0x7F);
                String ascii = new String(buf, 0, total, StandardCharsets.ISO_8859_1);
                if (ascii.contains("APIC") || ascii.contains("PIC\0")) {
                    return true;
                }
                // Only if the ID3 tag is larger than our 64KB window could APIC start later
                return (tagSize + 10) > total;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static Bitmap extractEmbeddedPictureQuietly(Context context, Uri audioUri, int reqSize) {
        if (!mayContainEmbeddedPicture(context, audioUri)) {
            return null;
        }
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(context, audioUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                String hasImage = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_IMAGE);
                if ("no".equalsIgnoreCase(hasImage)) {
                    return null;
                }
            }
            byte[] data = mmr.getEmbeddedPicture();
            if (data != null && data.length > 0) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, reqSize);
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int calculateSampleSize(int width, int height, int reqSize) {
        int sampleSize = 1;
        while ((width / sampleSize) > reqSize || (height / sampleSize) > reqSize) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    @ColorInt
    public static int resolveAccentColor(@Nullable String cacheKey, @Nullable Bitmap bitmap, Context context) {
        int fallback = ContextCompat.getColor(context, R.color.accent_blue);
        if (cacheKey != null && !cacheKey.isEmpty()) {
            Integer cached = ACCENT_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        if (bitmap == null) return fallback;
        Palette palette = Palette.from(bitmap).maximumColorCount(12).generate();
        int accent = extractVibrantAccent(palette, context);
        if (cacheKey != null && !cacheKey.isEmpty()) {
            ACCENT_CACHE.put(cacheKey, accent);
        }
        return accent;
    }

    @ColorInt
    public static int extractVibrantAccent(@Nullable Palette palette, Context context) {
        int fallback = ContextCompat.getColor(context, R.color.accent_blue);
        if (palette == null) return fallback;

        Palette.Swatch swatch = palette.getVibrantSwatch();
        if (swatch == null) swatch = palette.getLightVibrantSwatch();
        if (swatch == null) swatch = palette.getDarkVibrantSwatch();
        if (swatch == null) swatch = palette.getMutedSwatch();
        if (swatch == null) swatch = palette.getDominantSwatch();

        if (swatch == null) return fallback;

        int rawColor = swatch.getRgb();
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(rawColor, hsl);

        if (hsl[1] < 0.16f) {
            return fallback;
        }
        hsl[1] = Math.max(0.55f, Math.min(0.95f, hsl[1] * 1.15f));
        hsl[2] = Math.max(0.54f, Math.min(0.72f, hsl[2]));
        return ColorUtils.HSLToColor(hsl);
    }

    @Nullable
    public static byte[] compressToBytes(@Nullable Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
