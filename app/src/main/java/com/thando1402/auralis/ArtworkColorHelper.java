package com.thando1402.auralis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class ArtworkColorHelper {

    private ArtworkColorHelper() {}

    /**
     * Loads album art reliably on Android 10/11+ (including Nokia 1.4) using:
     * 1. ContentResolver.loadThumbnail (if API >= 29)
     * 2. ContentResolver.openInputStream (downsampled)
     * 3. MediaMetadataRetriever.getEmbeddedPicture() from the audio file URI
     */
    @Nullable
    public static Bitmap loadArtworkBitmap(Context context, @Nullable Uri artworkUri, @Nullable Uri audioUri, int reqSize) {
        if (context == null) return null;

        // Tier 1 & 2: Try artworkUri
        Bitmap bmp = decodeUri(context, artworkUri, reqSize);
        if (bmp != null) return bmp;

        // Tier 3: Fallback to embedded ID3/FLAC picture from audioUri
        if (audioUri != null) {
            bmp = extractEmbeddedPicture(context, audioUri, reqSize);
            if (bmp != null) return bmp;
            bmp = decodeUri(context, audioUri, reqSize);
        }
        return bmp;
    }

    @Nullable
    private static Bitmap decodeUri(Context context, @Nullable Uri uri, int reqSize) {
        if (uri == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.getContentResolver().loadThumbnail(uri, new Size(reqSize, reqSize), null);
            } catch (Exception ignored) {
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

            int sampleSize = 1;
            while ((bounds.outWidth / sampleSize) > reqSize || (bounds.outHeight / sampleSize) > reqSize) {
                sampleSize *= 2;
            }
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
    private static Bitmap extractEmbeddedPicture(Context context, Uri audioUri, int reqSize) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, audioUri);
            byte[] data = mmr.getEmbeddedPicture();
            if (data != null && data.length > 0) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

                int sampleSize = 1;
                while ((bounds.outWidth / sampleSize) > reqSize || (bounds.outHeight / sampleSize) > reqSize) {
                    sampleSize *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Extracts a guaranteed vibrant accent color from a Palette so UI controls and notifications
     * never turn dull grey or invisible dark black.
     */
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

        // If the cover art is monochrome/grey (saturation < 0.18), use the signature accent blue
        if (hsl[1] < 0.18f) {
            return fallback;
        }
        // Boost saturation and clamp lightness for dark-theme legibility
        hsl[1] = Math.max(0.55f, Math.min(0.95f, hsl[1] * 1.15f));
        hsl[2] = Math.max(0.54f, Math.min(0.72f, hsl[2]));
        return ColorUtils.HSLToColor(hsl);
    }

    @Nullable
    public static byte[] compressToBytes(@Nullable Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
