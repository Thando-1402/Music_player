package com.thando1402.auralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class SongMetadataManager {

    private static final String PREFS_NAME = "auralis_settings";
    private static final String KEY_STORAGE_PREF = "prefer_sd_card";
    private static final String JSON_FILE_NAME = "song_metadata_edits.json";
    private static final Object CACHE_LOCK = new Object();

    private static JSONObject cachedEdits = null;
    private static long lastModifiedTime = 0;

    public static void setPreferSdCard(Context context, boolean preferSd) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_STORAGE_PREF, preferSd).apply();
    }

    public static boolean isPreferSdCard(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_STORAGE_PREF, true);
    }

    public static File getStorageDirectory(Context context) {
        File[] externalDirs = context.getExternalFilesDirs(null);
        if (isPreferSdCard(context) && externalDirs.length > 1 && externalDirs[1] != null) {
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(externalDirs[1]))) {
                return externalDirs[1];
            }
        }
        if (externalDirs.length > 0 && externalDirs[0] != null) {
            return externalDirs[0];
        }
        return context.getFilesDir();
    }

    @NonNull
    private static File getAuralisRootFolder(Context context) {
        File base = getStorageDirectory(context);
        File root = new File(base, "Auralis");
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return root;
    }

    /**
     * Dedicated synced lyrics folder:
     * Android/data/com.thando1402.auralis/files/Auralis/Lyrics/
     */
    @NonNull
    public static File getDedicatedLyricsBackupDir(Context context) {
        File primaryExternal = context.getExternalFilesDir(null);
        File base = primaryExternal != null ? primaryExternal : context.getFilesDir();
        File lyricsDir = new File(new File(base, "Auralis"), "Lyrics");
        if (!lyricsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            lyricsDir.mkdirs();
        }
        return lyricsDir;
    }

    /**
     * Active AI-synced / edited .lrc folder:
     * .../Auralis/Lyrics_Synced/
     */
    @NonNull
    public static File getSyncedLyricsDir(Context context) {
        File dir = new File(getAuralisRootFolder(context), "Lyrics_Synced");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Permanent untouched original lyrics backup folder:
     * .../Auralis/Lyrics_Original_Backup/
     */
    @NonNull
    public static File getBackupLyricsDir(Context context) {
        File dir = new File(getAuralisRootFolder(context), "Lyrics_Original_Backup");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static String sanitizeFileSegment(String input) {
        if (input == null) return "Track";
        String cleaned = input.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64).trim();
        }
        return cleaned.isEmpty() ? "Track" : cleaned;
    }

    @NonNull
    private static String buildLrcFileName(String title, String artist, String uriKey) {
        String safeArtist = sanitizeFileSegment(artist);
        String safeTitle = sanitizeFileSegment(title);
        int hash = Math.abs(uriKey != null ? uriKey.hashCode() : 0);
        return safeArtist + " - " + safeTitle + "_" + hash + ".lrc";
    }

    @NonNull
    public static File getBackupLrcFileForSong(Context context, String title, String artist, String uriKey) {
        return new File(getDedicatedLyricsBackupDir(context), buildLrcFileName(title, artist, uriKey));
    }

    @NonNull
    public static File getSyncedLrcFileForSong(Context context, String title, String artist, String uriKey) {
        return new File(getSyncedLyricsDir(context), buildLrcFileName(title, artist, uriKey));
    }

    @NonNull
    public static File getOriginalBackupLrcFileForSong(Context context, String title, String artist, String uriKey) {
        return new File(getBackupLyricsDir(context), buildLrcFileName(title, artist, uriKey));
    }

    private static File getStableMetadataFile(Context context) {
        File stableFile = new File(context.getFilesDir(), JSON_FILE_NAME);
        if (!stableFile.exists()) {
            File[] externalDirs = context.getExternalFilesDirs(null);
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

    /**
     * Applies saved metadata overrides and optionally resolves full lyrics from:
     * 1. Active Synced folder (Auralis/Lyrics_Synced/) & Dedicated folder (Auralis/Lyrics/)
     * 2. Original Backup folder (Auralis/Lyrics_Original_Backup/) & JSON metadata cache
     * 3. Sidecar .lrc next to original audio or embedded ID3 USLT / Vorbis LYRICS tags
     */
    public static void applySavedEdits(Context context, Song song, boolean resolveFullLyrics) {
        if (context == null || song == null || song.getUri() == null) return;
        try {
            String key = song.getUri().toString();
            JSONObject edit;
            synchronized (CACHE_LOCK) {
                JSONObject allEdits = loadJsonLocked(context);
                edit = allEdits.optJSONObject(key);
            }

            if (edit != null) {
                song.setTitle(edit.optString("title", song.getTitle()));
                song.setArtist(edit.optString("artist", song.getArtist()));
                song.setAlbum(edit.optString("album", song.getAlbum()));
                song.setTrackNumber(edit.optString("trackNumber", song.getTrackNumber()));
                song.setYearGenre(edit.optString("yearGenre", song.getYearGenre()));
                song.setLrcOffsetMs(edit.optLong("lrcOffsetMs", 0L));
                song.setLyrics(edit.optString("lyrics", ""));

                String coverPath = edit.optString("coverPath", "");
                if (!coverPath.isEmpty()) {
                    File coverFile = new File(coverPath);
                    if (coverFile.exists()) {
                        song.setCustomCoverUri(Uri.fromFile(coverFile));
                    }
                }
            }

            if (resolveFullLyrics) {
                String resolvedLyrics = resolveLyricsWithFallback(context, song, edit);
                song.setLyrics(resolvedLyrics);
            }
        } catch (Exception ignored) {
        }
    }

    @NonNull
    public static String resolveLyricsWithFallback(Context context, Song song, @Nullable JSONObject edit) {
        if (context == null || song == null || song.getUri() == null) return "";
        String key = song.getUri().toString();

        // Priority 1A: Active Synced LRC file in Auralis/Lyrics_Synced/
        if (edit != null) {
            String syncedPath = edit.optString("syncedLrcPath", "");
            if (!syncedPath.isEmpty()) {
                String text = readTextFile(new File(syncedPath));
                if (!text.isEmpty()) return text;
            }
        }
        String fromSynced = readTextFile(getSyncedLrcFileForSong(context, song.getTitle(), song.getArtist(), key));
        if (!fromSynced.isEmpty()) return fromSynced;

        // Priority 1B: Dedicated folder in Android/data/com.thando1402.auralis/files/Auralis/Lyrics/
        if (edit != null) {
            String storedPath = edit.optString("lrcBackupPath", "");
            if (!storedPath.isEmpty()) {
                String text = readTextFile(new File(storedPath));
                if (!text.isEmpty()) return text;
            }
        }
        String fromHashed = readTextFile(getBackupLrcFileForSong(context, song.getTitle(), song.getArtist(), key));
        if (!fromHashed.isEmpty()) return fromHashed;

        // Priority 1C: User-dropped "<Artist> - <Title>.lrc" or "<Title>.lrc" in Lyrics_Synced/ or Lyrics/
        File[] dropDirs = new File[]{getSyncedLyricsDir(context), getDedicatedLyricsBackupDir(context)};
        for (File dir : dropDirs) {
            File artistTitleFile = new File(dir,
                    sanitizeFileSegment(song.getArtist()) + " - " + sanitizeFileSegment(song.getTitle()) + ".lrc");
            String fromArtistTitle = readTextFile(artistTitleFile);
            if (!fromArtistTitle.isEmpty()) return fromArtistTitle;

            File titleOnlyFile = new File(dir, sanitizeFileSegment(song.getTitle()) + ".lrc");
            String fromTitleOnly = readTextFile(titleOnlyFile);
            if (!fromTitleOnly.isEmpty()) return fromTitleOnly;
        }

        // Priority 2: JSON-stored lyrics or Original Backup folder
        if (edit != null) {
            String jsonLyrics = edit.optString("lyrics", "").trim();
            if (!jsonLyrics.isEmpty()) return jsonLyrics;
        }
        String fromOriginalBackup = readTextFile(getOriginalBackupLrcFileForSong(context, song.getTitle(), song.getArtist(), key));
        if (!fromOriginalBackup.isEmpty()) return fromOriginalBackup;

        if (song.getLyrics() != null && !song.getLyrics().trim().isEmpty()) {
            return song.getLyrics().trim();
        }

        // Priority 3: Sidecar .lrc next to audio file or embedded ID3v2 USLT / Vorbis LYRICS tags
        String sidecar = readSidecarLrcFromMediaStore(context, song.getUri());
        if (!sidecar.isEmpty()) return sidecar;

        return extractEmbeddedLyricsTag(context, song.getUri());
    }

    public static void saveSongEdit(Context context, Song song, String newTitle, String newArtist, Uri pickedImageUri, String lyrics) {
        saveSongEditFull(context, song, newTitle, newArtist,
                song != null ? song.getAlbum() : "",
                song != null ? song.getTrackNumber() : "",
                song != null ? song.getYearGenre() : "",
                pickedImageUri, lyrics);
    }

    public static void saveSongEditFull(
            Context context,
            Song song,
            String newTitle,
            String newArtist,
            String newAlbum,
            String newTrackNumber,
            String newYearGenre,
            @Nullable Uri pickedImageUri,
            @Nullable String lyrics) {
        if (context == null || song == null || song.getUri() == null) return;
        try {
            String key = song.getUri().toString();
            File savedCover = null;
            if (pickedImageUri != null && !"file".equals(pickedImageUri.getScheme())) {
                savedCover = compressAndSaveCover(context, pickedImageUri, key.hashCode());
            } else if (pickedImageUri != null && pickedImageUri.getPath() != null) {
                File f = new File(pickedImageUri.getPath());
                if (f.exists()) {
                    if (f.getAbsolutePath().contains(context.getCacheDir().getAbsolutePath())) {
                        savedCover = compressAndSaveCover(context, pickedImageUri, key.hashCode());
                    } else {
                        savedCover = f;
                    }
                }
            }

            File syncedLrcFile = null;
            File dedicatedLrcFile = null;
            File originalBackupFile = getOriginalBackupLrcFileForSong(context, newTitle, newArtist, key);

            if (lyrics != null && !lyrics.trim().isEmpty()) {
                // Preserve untouched original backup before overwriting active synced lyrics
                if (!originalBackupFile.exists() || originalBackupFile.length() == 0) {
                    String priorOriginal = readSidecarLrcFromMediaStore(context, song.getUri());
                    if (priorOriginal.isEmpty()) {
                        priorOriginal = extractEmbeddedLyricsTag(context, song.getUri());
                    }
                    if (priorOriginal.isEmpty()) {
                        priorOriginal = readTextFile(getSyncedLrcFileForSong(context, song.getTitle(), song.getArtist(), key));
                    }
                    if (priorOriginal.isEmpty()) {
                        priorOriginal = lyrics.trim();
                    }
                    writeTextFile(originalBackupFile, priorOriginal);
                }

                syncedLrcFile = getSyncedLrcFileForSong(context, newTitle, newArtist, key);
                dedicatedLrcFile = getBackupLrcFileForSong(context, newTitle, newArtist, key);
                writeTextFile(syncedLrcFile, lyrics.trim());
                writeTextFile(dedicatedLrcFile, lyrics.trim());
            }

            synchronized (CACHE_LOCK) {
                JSONObject allEdits = loadJsonLocked(context);
                JSONObject edit = allEdits.optJSONObject(key);
                if (edit == null) edit = new JSONObject();

                edit.put("title", newTitle);
                edit.put("artist", newArtist);
                if (newAlbum != null) edit.put("album", newAlbum);
                if (newTrackNumber != null) edit.put("trackNumber", newTrackNumber);
                if (newYearGenre != null) edit.put("yearGenre", newYearGenre);

                if (lyrics != null) {
                    edit.put("lyrics", lyrics.trim());
                    song.setLyrics(lyrics.trim());
                }
                if (syncedLrcFile != null) {
                    edit.put("syncedLrcPath", syncedLrcFile.getAbsolutePath());
                }
                if (dedicatedLrcFile != null) {
                    edit.put("lrcBackupPath", dedicatedLrcFile.getAbsolutePath());
                }
                if (originalBackupFile.exists()) {
                    edit.put("originalBackupPath", originalBackupFile.getAbsolutePath());
                }
                if (savedCover != null) {
                    edit.put("coverPath", savedCover.getAbsolutePath());
                    song.setCustomCoverUri(Uri.fromFile(savedCover));
                    ArtworkColorHelper.invalidateCache(key);
                }

                song.setTitle(newTitle);
                song.setArtist(newArtist);
                song.setAlbum(newAlbum);
                song.setTrackNumber(newTrackNumber);
                song.setYearGenre(newYearGenre);

                allEdits.put(key, edit);
                saveJsonLocked(context, allEdits);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Restores the untouched original backup lyrics (from Lyrics_Original_Backup/, sidecar .lrc,
     * or embedded ID3/Vorbis tags) back into active Lyrics_Synced/ and Lyrics/.
     */
    @Nullable
    public static String restoreOriginalBackupLyrics(Context context, String songUriKey, String title, String artist) {
        if (context == null || songUriKey == null || songUriKey.isEmpty()) return null;
        try {
            String original = "";
            synchronized (CACHE_LOCK) {
                JSONObject allEdits = loadJsonLocked(context);
                JSONObject edit = allEdits.optJSONObject(songUriKey);
                if (edit != null) {
                    String origPath = edit.optString("originalBackupPath", "");
                    if (!origPath.isEmpty()) {
                        original = readTextFile(new File(origPath));
                    }
                }
            }
            if (original.isEmpty()) {
                original = readTextFile(getOriginalBackupLrcFileForSong(context, title, artist, songUriKey));
            }
            if (original.isEmpty()) {
                Uri audioUri = Uri.parse(songUriKey);
                original = readSidecarLrcFromMediaStore(context, audioUri);
                if (original.isEmpty()) {
                    original = extractEmbeddedLyricsTag(context, audioUri);
                }
            }
            if (original.isEmpty()) return null;

            File syncedFile = getSyncedLrcFileForSong(context, title, artist, songUriKey);
            File dedicatedFile = getBackupLrcFileForSong(context, title, artist, songUriKey);
            writeTextFile(syncedFile, original);
            writeTextFile(dedicatedFile, original);

            synchronized (CACHE_LOCK) {
                JSONObject allEdits = loadJsonLocked(context);
                JSONObject edit = allEdits.optJSONObject(songUriKey);
                if (edit == null) edit = new JSONObject();
                edit.put("lyrics", original);
                edit.put("lrcOffsetMs", 0L);
                edit.put("syncedLrcPath", syncedFile.getAbsolutePath());
                edit.put("lrcBackupPath", dedicatedFile.getAbsolutePath());
                allEdits.put(songUriKey, edit);
                saveJsonLocked(context, allEdits);
            }
            return original;
        } catch (Exception e) {
            return null;
        }
    }

    public static long getLrcOffsetMs(Context context, String songUriKey) {
        if (context == null || songUriKey == null || songUriKey.isEmpty()) return 0L;
        synchronized (CACHE_LOCK) {
            JSONObject allEdits = loadJsonLocked(context);
            JSONObject edit = allEdits.optJSONObject(songUriKey);
            return edit != null ? edit.optLong("lrcOffsetMs", 0L) : 0L;
        }
    }

    public static void setLrcOffsetMs(Context context, String songUriKey, long offsetMs) {
        if (context == null || songUriKey == null || songUriKey.isEmpty()) return;
        synchronized (CACHE_LOCK) {
            try {
                JSONObject allEdits = loadJsonLocked(context);
                JSONObject edit = allEdits.optJSONObject(songUriKey);
                if (edit == null) edit = new JSONObject();
                edit.put("lrcOffsetMs", offsetMs);
                allEdits.put(songUriKey, edit);
                saveJsonLocked(context, allEdits);
            } catch (Exception ignored) {
            }
        }
    }

    public static long getLyricsOffsetMs(Context context, String songUriKey) {
        return getLrcOffsetMs(context, songUriKey);
    }

    public static void setLyricsOffsetMs(Context context, String songUriKey, long offsetMs) {
        setLrcOffsetMs(context, songUriKey, offsetMs);
    }

    public static boolean isFavorite(Context context, String songUriKey) {
        if (songUriKey == null || songUriKey.isEmpty()) return false;
        synchronized (CACHE_LOCK) {
            JSONObject allEdits = loadJsonLocked(context);
            JSONObject edit = allEdits.optJSONObject(songUriKey);
            return edit != null && edit.optBoolean("favorite", false);
        }
    }

    public static void setFavorite(Context context, String songUriKey, boolean favorite) {
        if (songUriKey == null || songUriKey.isEmpty()) return;
        synchronized (CACHE_LOCK) {
            try {
                JSONObject allEdits = loadJsonLocked(context);
                JSONObject edit = allEdits.optJSONObject(songUriKey);
                if (edit == null) edit = new JSONObject();
                edit.put("favorite", favorite);
                allEdits.put(songUriKey, edit);
                saveJsonLocked(context, allEdits);
            } catch (Exception ignored) {
            }
        }
    }

    public static void removeCustomCover(Context context, String songUriKey) {
        if (songUriKey == null || songUriKey.isEmpty()) return;
        synchronized (CACHE_LOCK) {
            try {
                JSONObject allEdits = loadJsonLocked(context);
                JSONObject edit = allEdits.optJSONObject(songUriKey);
                if (edit != null) {
                    edit.remove("coverPath");
                    allEdits.put(songUriKey, edit);
                    saveJsonLocked(context, allEdits);
                }
                ArtworkColorHelper.invalidateCache(songUriKey);
            } catch (Exception ignored) {
            }
        }
    }

    private static String readSidecarLrcFromMediaStore(Context context, Uri audioUri) {
        try (Cursor c = context.getContentResolver().query(
                audioUri, new String[]{MediaStore.Audio.Media.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String dataPath = c.getString(0);
                if (dataPath != null && !dataPath.isEmpty()) {
                    int dot = dataPath.lastIndexOf('.');
                    String lrcPath = (dot > 0 ? dataPath.substring(0, dot) : dataPath) + ".lrc";
                    return readTextFile(new File(lrcPath));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Lightweight header scanner for embedded ID3v2 USLT/SYLT or FLAC/OGG Vorbis LYRICS tags
     * (reads at most the first 256 KB so Nokia 1.4 RAM is unaffected).
     */
    private static String extractEmbeddedLyricsTag(Context context, Uri audioUri) {
        try (InputStream is = context.getContentResolver().openInputStream(audioUri)) {
            if (is == null) return "";
            byte[] header = new byte[262_144];
            int totalRead = 0;
            while (totalRead < header.length) {
                int r = is.read(header, totalRead, header.length - totalRead);
                if (r <= 0) break;
                totalRead += r;
            }
            if (totalRead < 16) return "";

            // Check ID3v2 USLT frame
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                int version = header[3] & 0xFF;
                int pos = 10;
                while (pos + 10 < totalRead) {
                    if (header[pos] == 0) break;
                    String frameId = new String(header, pos, 4, StandardCharsets.ISO_8859_1);
                    int frameSize;
                    if (version == 4) {
                        frameSize = ((header[pos + 4] & 0x7F) << 21)
                                | ((header[pos + 5] & 0x7F) << 14)
                                | ((header[pos + 6] & 0x7F) << 7)
                                | (header[pos + 7] & 0x7F);
                    } else {
                        frameSize = ((header[pos + 4] & 0xFF) << 24)
                                | ((header[pos + 5] & 0xFF) << 16)
                                | ((header[pos + 6] & 0xFF) << 8)
                                | (header[pos + 7] & 0xFF);
                    }
                    if (frameSize <= 0 || pos + 10 + frameSize > totalRead) break;
                    if ("USLT".equals(frameId) && frameSize > 4) {
                        int enc = header[pos + 10] & 0xFF;
                        int start = pos + 14; // skip encoding (1) + language (3)
                        int end = pos + 10 + frameSize;
                        if (enc == 1 || enc == 2) {
                            while (start + 1 < end && !(header[start] == 0 && header[start + 1] == 0)) {
                                start += 2;
                            }
                            start += 2;
                            if (start < end) {
                                return new String(header, start, end - start,
                                        enc == 1 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_16BE).trim();
                            }
                        } else {
                            while (start < end && header[start] != 0) start++;
                            start++;
                            if (start < end) {
                                return new String(header, start, end - start,
                                        enc == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1).trim();
                            }
                        }
                    }
                    pos += 10 + frameSize;
                }
            }

            // Check FLAC/Vorbis comment "LYRICS=" or "UNSYNCEDLYRICS="
            String rawAscii = new String(header, 0, totalRead, StandardCharsets.ISO_8859_1);
            int vorbisIdx = rawAscii.toUpperCase(Locale.ROOT).indexOf("LYRICS=");
            if (vorbisIdx >= 4) {
                int lenPos = vorbisIdx - 4;
                if (vorbisIdx >= 11 && rawAscii.regionMatches(true, vorbisIdx - 8, "UNSYNCED", 0, 8)) {
                    lenPos = vorbisIdx - 12;
                }
                if (lenPos >= 0) {
                    int commentLen = (header[lenPos] & 0xFF)
                            | ((header[lenPos + 1] & 0xFF) << 8)
                            | ((header[lenPos + 2] & 0xFF) << 16)
                            | ((header[lenPos + 3] & 0xFF) << 24);
                    int eqPos = vorbisIdx + 7;
                    int valEnd = lenPos + 4 + commentLen;
                    if (commentLen > 7 && valEnd <= totalRead && eqPos < valEnd) {
                        return new String(header, eqPos, valEnd - eqPos, StandardCharsets.UTF_8).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String readTextFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return "";
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeTextFile(File file, String content) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static File compressAndSaveCover(Context context, Uri imageUri, int songHash) {
        try {
            Bitmap original = ArtworkColorHelper.loadArtworkBitmap(context, imageUri, null, 720);
            if (original == null) return null;

            int width = original.getWidth();
            int height = original.getHeight();
            int cropSize = Math.min(width, height);
            int xOffset = (width - cropSize) / 2;
            int yOffset = (height - cropSize) / 2;

            Bitmap squareCrop = Bitmap.createBitmap(original, xOffset, yOffset, cropSize, cropSize);
            if (squareCrop != original) original.recycle();

            int targetDim = Math.min(720, cropSize);
            Bitmap scaled = Bitmap.createScaledBitmap(squareCrop, targetDim, targetDim, true);
            if (scaled != squareCrop) squareCrop.recycle();

            File coversDir = new File(getStorageDirectory(context), "covers");
            if (!coversDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                coversDir.mkdirs();
            }

            File outFile = new File(coversDir, "cover_" + Math.abs(songHash) + "_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            scaled.recycle();
            return outFile;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject loadJsonLocked(Context context) {
        File f = getStableMetadataFile(context);
        if (cachedEdits != null && f.lastModified() == lastModifiedTime) {
            return cachedEdits;
        }
        cachedEdits = readFileToJson(f);
        lastModifiedTime = f.lastModified();
        return cachedEdits;
    }

    private static void saveJsonLocked(Context context, JSONObject json) {
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
        } catch (Exception ignored) {
        }
    }
}
