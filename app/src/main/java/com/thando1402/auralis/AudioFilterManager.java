package com.thando1402.auralis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class AudioFilterManager {

    private static final String PREFS_NAME = "auralis_settings";
    private static final String KEY_HIDE_WHATSAPP = "hide_whatsapp_audio";
    private static final String KEY_EXCLUDED_FOLDERS = "excluded_folder_paths";

    // Matches WhatsApp filenames like AUD-20230130-WA0016 or PTT-20240101-WA0001
    private static final Pattern WHATSAPP_FILE_PATTERN =
            Pattern.compile("^(?i)(aud|ptt)-\\d{8}-wa\\d+.*");

    public static boolean isHideWhatsAppEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_HIDE_WHATSAPP, true); // Enabled by default
    }

    public static void setHideWhatsAppEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HIDE_WHATSAPP, enabled).apply();
    }

    public static Set<String> getExcludedFolders(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_EXCLUDED_FOLDERS, new HashSet<>()));
    }

    public static void addExcludedFolder(Context context, String folderPath) {
        Set<String> set = getExcludedFolders(context);
        set.add(folderPath);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_EXCLUDED_FOLDERS, set).apply();
    }

    public static void clearExcludedFolders(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_EXCLUDED_FOLDERS).apply();
    }

    private static final String KEY_IGNORE_HIDDEN = "ignore_hidden";
    private static final String KEY_RESPECT_NOMEDIA = "respect_nomedia";
    private static final String KEY_FILTER_SHORT_CLIPS = "filter_short_clips";
    private static final String KEY_EXCLUDE_PODCASTS = "exclude_podcasts";

    public static boolean isIgnoreHiddenEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IGNORE_HIDDEN, true);
    }
    public static void setIgnoreHiddenEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IGNORE_HIDDEN, enabled).apply();
    }

    public static boolean isRespectNomediaEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RESPECT_NOMEDIA, true);
    }
    public static void setRespectNomediaEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RESPECT_NOMEDIA, enabled).apply();
    }

    public static boolean isFilterShortClipsEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FILTER_SHORT_CLIPS, true);
    }
    public static void setFilterShortClipsEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FILTER_SHORT_CLIPS, enabled).apply();
    }

    public static boolean isExcludePodcastsEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXCLUDE_PODCASTS, false);
    }
    public static void setExcludePodcastsEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EXCLUDE_PODCASTS, enabled).apply();
    }

    public static boolean shouldExcludeTrack(Context context, String title, String fullPath) {
        return shouldExcludeTrack(context, title, fullPath, 30000L);
    }

    public static boolean shouldExcludeTrack(Context context, String title, String fullPath, long durationMs) {
        String safeTitle = title != null ? title.trim() : "";
        String safePath = fullPath != null ? fullPath : "";
        String lowerPath = safePath.toLowerCase(Locale.US);

        if (isIgnoreHiddenEnabled(context) && lowerPath.contains("/.")) {
            return true;
        }

        if (isFilterShortClipsEnabled(context) && durationMs > 0 && durationMs < 30000) {
            return true;
        }

        if (isExcludePodcastsEnabled(context) && (lowerPath.contains("/podcasts") || lowerPath.contains("/audiobooks"))) {
            return true;
        }

        // 1. Check user-excluded folder paths
        Set<String> excludedDirs = getExcludedFolders(context);
        for (String excluded : excludedDirs) {
            if (!excluded.isEmpty() && safePath.startsWith(excluded)) {
                return true;
            }
        }

        // 2. Check WhatsApp folder paths and AUD-YYYYMMDD-WAXXXX filenames
        if (isHideWhatsAppEnabled(context)) {
            if (lowerPath.contains("/whatsapp") || lowerPath.contains("whatsapp audio")
                    || lowerPath.contains("whatsapp voice")) {
                return true;
            }
            if (WHATSAPP_FILE_PATTERN.matcher(safeTitle).matches()) {
                return true;
            }
            if (safePath.isEmpty() && safeTitle.toUpperCase(Locale.US).startsWith("AUD-")
                    && safeTitle.toUpperCase(Locale.US).contains("-WA")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMicroSdPath(String path) {
        if (path == null || path.isEmpty()) return false;
        // Internal storage on Android is /storage/emulated/0 or /data
        if (path.startsWith("/storage/emulated") || path.startsWith("/data") || path.startsWith("/sdcard")) {
            return false;
        }
        // Removable SD cards mount under /storage/XXXX-XXXX
        return path.startsWith("/storage/");
    }
}