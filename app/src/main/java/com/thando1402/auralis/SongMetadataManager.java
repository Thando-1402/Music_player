package com.thando1402.auralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.util.Iterator;

public class SongMetadataManager {

    private static final String PREFS_NAME = "auralis_settings";
    private static final String KEY_STORAGE_PREF = "prefer_sd_card";
    private static final String JSON_FILE_NAME = "song_metadata_edits.json";

    public static void setPreferSdCard(Context context, boolean preferSd) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_STORAGE_PREF, preferSd).apply();
    }

    public static boolean isPreferSdCard(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_STORAGE_PREF, true); // Default to SD card to save phone space
    }

    public static File getStorageDirectory(Context context) {
        File[] externalDirs = ContextCompat.getExternalFilesDirs(context, null);
        if (isPreferSdCard(context) && externalDirs.length > 1 && externalDirs[1] != null) {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(externalDirs[1]))) {
                return externalDirs[1]; // Physical MicroSD Card
            }
        }
        if (externalDirs.length > 0 && externalDirs[0] != null) {
            return externalDirs[0];
        }
        return context.getFilesDir();
    }

    private static File getStableMetadataFile(Context context) {
        File stableFile = new File(context.getFilesDir(), JSON_FILE_NAME);
        if (!stableFile.exists()) {
            // Migrate any existing JSON from external/SD directories
            File[] externalDirs = ContextCompat.getExternalFilesDirs(context, null);
            for (File dir : externalDirs) {
                if (dir != null) {
                    File legacyFile = new File(dir, JSON_FILE_NAME);
                    if (legacyFile.exists()) {
                        JSONObject legacyJson = readFileToJson(legacyFile);
                        writeJsonToFile(stableFile, legacyJson);
                        break;
                    }
                }
            }
        }
        return stableFile;
    }

    public static void applySavedEdits(Context context, Song song) {
        try {
            JSONObject allEdits = loadJson(context);
            String key = song.getUri().toString();
            if (allEdits.has(key)) {
                JSONObject edit = allEdits.getJSONObject(key);
                song.setTitle(edit.optString("title", song.getTitle()));
                song.setArtist(edit.optString("artist", song.getArtist()));
                song.setLyrics(edit.optString("lyrics", ""));
                String coverPath = edit.optString("coverPath", "");
                if (!coverPath.isEmpty()) {
                    File coverFile = new File(coverPath);
                    if (coverFile.exists()) {
                        song.setCustomCoverUri(Uri.fromFile(coverFile));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveSongEdit(Context context, Song song, String newTitle, String newArtist, Uri pickedImageUri, String lyrics) {
        try {
            JSONObject allEdits = loadJson(context);
            String key = song.getUri().toString();
            JSONObject edit = allEdits.optJSONObject(key);
            if (edit == null) edit = new JSONObject();

            edit.put("title", newTitle);
            edit.put("artist", newArtist);
            if (lyrics != null) {
                edit.put("lyrics", lyrics);
                song.setLyrics(lyrics);
            }

            if (pickedImageUri != null) {
                File savedCover = compressAndSaveCover(context, pickedImageUri, key.hashCode());
                if (savedCover != null) {
                    edit.put("coverPath", savedCover.getAbsolutePath());
                    song.setCustomCoverUri(Uri.fromFile(savedCover));
                }
            }

            song.setTitle(newTitle);
            song.setArtist(newArtist);

            allEdits.put(key, edit);
            saveJson(context, allEdits);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isFavorite(Context context, String songUriKey) {
        if (songUriKey == null || songUriKey.isEmpty()) return false;
        JSONObject allEdits = loadJson(context);
        JSONObject edit = allEdits.optJSONObject(songUriKey);
        return edit != null && edit.optBoolean("favorite", false);
    }

    public static void setFavorite(Context context, String songUriKey, boolean favorite) {
        if (songUriKey == null || songUriKey.isEmpty()) return;
        try {
            JSONObject allEdits = loadJson(context);
            JSONObject edit = allEdits.optJSONObject(songUriKey);
            if (edit == null) edit = new JSONObject();
            edit.put("favorite", favorite);
            allEdits.put(songUriKey, edit);
            saveJson(context, allEdits);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void removeCustomCover(Context context, String songUriKey) {
        if (songUriKey == null || songUriKey.isEmpty()) return;
        try {
            JSONObject allEdits = loadJson(context);
            JSONObject edit = allEdits.optJSONObject(songUriKey);
            if (edit != null) {
                edit.remove("coverPath");
                allEdits.put(songUriKey, edit);
                saveJson(context, allEdits);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static File compressAndSaveCover(Context context, Uri imageUri, int songHash) {
        try (InputStream in = context.getContentResolver().openInputStream(imageUri)) {
            if (in == null) return null;
            Bitmap original = BitmapFactory.decodeStream(in);
            if (original == null) return null;

            Bitmap scaled = Bitmap.createScaledBitmap(original, 300, 300, true);
            if (scaled != original) original.recycle();

            File coversDir = new File(getStorageDirectory(context), "covers");
            if (!coversDir.exists()) coversDir.mkdirs();

            File outFile = new File(coversDir, "cover_" + Math.abs(songHash) + ".jpg");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
            }
            scaled.recycle();
            return outFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static JSONObject cachedEdits = null;
    private static long lastModifiedTime = 0;

    private static JSONObject loadJson(Context context) {
        File f = getStableMetadataFile(context);
        if (cachedEdits != null && f.lastModified() == lastModifiedTime) {
            return cachedEdits;
        }
        cachedEdits = readFileToJson(f);
        lastModifiedTime = f.lastModified();
        return cachedEdits;
    }

    private static void saveJson(Context context, JSONObject json) {
        File f = getStableMetadataFile(context);
        writeJsonToFile(f, json);
        cachedEdits = json;
        lastModifiedTime = f.lastModified();
    }

    private static JSONObject readFileToJson(File file) {
        if (file == null || !file.exists()) return new JSONObject();
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void writeJsonToFile(File file, JSONObject json) {
        if (file == null) return;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}