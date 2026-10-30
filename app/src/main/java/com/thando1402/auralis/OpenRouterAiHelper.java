package com.thando1402.auralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenRouterAiHelper {

    public static final String PREFS_NAME = "auralis_settings";
    public static final String KEY_PROFILE_NAME = "profile_display_name";
    private static final String KEY_FIRST_LAUNCH_PROMPTED = "profile_welcome_prompted";
    private static boolean appCheckInitialized = false;

    private static final Pattern UNIVERSAL_LRC_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET_TAG = Pattern.compile("(?i)\\[offset:\\s*([+-]?\\d+)\\s*]");

    public interface MetadataCallback {
        void onResult(String cleanTitle, String cleanArtist, boolean usedCloudAi);
    }

    public interface LyricsSyncCallback {
        void onSynced(String syncedLrc, boolean usedCloudAi);
    }

    public static synchronized void initFirebaseAppCheck(Context context) {
        if (appCheckInitialized) return;
        try {
            FirebaseApp.initializeApp(context.getApplicationContext());
            // Install debug AppCheck provider reflectively so debugImplementation does not leak into release APK
            Class<?> appCheckClass = Class.forName("com.google.firebase.appcheck.FirebaseAppCheck");
            Class<?> factoryBaseClass = Class.forName("com.google.firebase.appcheck.AppCheckProviderFactory");
            Class<?> debugFactoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory");

            Object appCheckInstance = appCheckClass.getMethod("getInstance").invoke(null);
            Object debugFactoryInstance = debugFactoryClass.getMethod("getInstance").invoke(null);
            Method installMethod = appCheckClass.getMethod("installAppCheckProviderFactory", factoryBaseClass);
            installMethod.invoke(appCheckInstance, (Object) debugFactoryInstance);
            appCheckInitialized = true;
        } catch (Exception ignored) {
            // Release builds or unconfigured environments safely skip debug AppCheck
        }
    }

    public static String getApiKey(Context context) {
        if (context == null) return "";
        try {
            String buildKey = context.getString(R.string.default_openrouter_key);
            if (!buildKey.trim().isEmpty()) {
                return buildKey.trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static boolean isProfileConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_PROFILE_NAME, "");
        if (!saved.trim().isEmpty() && !"Alex Vance".equalsIgnoreCase(saved.trim())) {
            return true;
        }
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean shouldShowProfileWelcome(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_FIRST_LAUNCH_PROMPTED, false) && !isProfileConfigured(context);
    }

    public static void markProfileWelcomeShown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_PROMPTED, true).apply();
    }

    public static void setProfileName(Context context, String name) {
        String fallback = context.getString(R.string.profile_default_name);
        String clean = (name != null && !name.trim().isEmpty()) ? name.trim() : fallback;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PROFILE_NAME, clean)
                .putBoolean(KEY_FIRST_LAUNCH_PROMPTED, true)
                .apply();
    }

    public static String getProfileName(Context context) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
                return user.getDisplayName().trim();
            }
        } catch (Exception ignored) {
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_PROFILE_NAME, "");
        if (!stored.trim().isEmpty() && !"Alex Vance".equalsIgnoreCase(stored.trim())) {
            return stored.trim();
        }
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.getEmail() != null && user.getEmail().contains("@")) {
                return user.getEmail().substring(0, user.getEmail().indexOf('@'));
            }
        } catch (Exception ignored) {
        }
        return context.getString(R.string.profile_default_name);
    }

    /**
     * 3-Tier Dual-AI Failover Router for Tag Cleaning:
     * Tier 1: OpenRouter (Llama 3.3 70B Free)
     * Tier 2: Official Firebase AI Logic SDK (Gemini Developer API)
     * Tier 3: Offline Smart RegEx Parser
     */
    public static void autoCleanSongMetadata(Context context, String rawTitle, String rawArtist, MetadataCallback callback) {
        initFirebaseAppCheck(context);
        final String openRouterKey = getApiKey(context);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                if (!openRouterKey.isEmpty()) {
                    String[] orResult = callOpenRouterModel(openRouterKey, rawTitle, rawArtist);
                    if (orResult != null) {
                        mainHandler.post(() -> callback.onResult(orResult[0], orResult[1], true));
                        return;
                    }
                }

                String[] firebaseResult = callFirebaseAiSdk(rawTitle, rawArtist);
                if (firebaseResult != null) {
                    mainHandler.post(() -> callback.onResult(firebaseResult[0], firebaseResult[1], true));
                    return;
                }

                String[] offline = cleanOffline(rawTitle, rawArtist);
                mainHandler.post(() -> callback.onResult(offline[0], offline[1], false));
            } finally {
                executor.shutdown();
            }
        });
    }

    /**
     * AI + Universal Lyrics Synchronizer:
     * Fixes drifted .lrc timestamps or generates timed [mm:ss.xx] LRC lines matched to exact trackDurationMs.
     * Falls back seamlessly to offline universal LRC normalizer & duration rescaler when offline.
     */
    public static void autoSyncAndFormatLyrics(
            Context context,
            String title,
            String artist,
            long durationMs,
            String rawLyrics,
            LyricsSyncCallback callback) {
        initFirebaseAppCheck(context);
        final String openRouterKey = getApiKey(context);
        final Context appCtx = context.getApplicationContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                long safeDurSec = Math.max(30L, durationMs > 0 ? durationMs / 1000L : 210L);
                String durationFormatted = String.format(Locale.US, "%02d:%02d", safeDurSec / 60, safeDurSec % 60);

                String prompt = "You are a precision LRC lyrics timing engine. "
                        + "Track: \"" + title + "\" by \"" + artist + "\", exact audio duration: " + durationFormatted + " (" + safeDurSec + " seconds).\n"
                        + ((rawLyrics != null && !rawLyrics.trim().isEmpty())
                        ? "Fix, align, and normalize the following lyrics into strict [mm:ss.xx] LRC format so every line fits naturally within 00:00.00 to " + durationFormatted + ".00:\n\n" + rawLyrics
                        : "Generate accurate time-synced [mm:ss.xx] LRC lyrics for this song if well-known, or structured instrumental/vocal markers fitting " + durationFormatted + ".")
                        + "\nRespond ONLY with raw [mm:ss.xx] LRC lines (no markdown code fences, no commentary).";

                if (PlaybackStateHelper.isOnline(appCtx)) {
                    if (!openRouterKey.isEmpty()) {
                        String aiLrc = callOpenRouterText(openRouterKey, prompt);
                        if (aiLrc != null && aiLrc.contains("[")) {
                            String normalized = normalizeLrcUniversal(aiLrc, durationMs);
                            mainHandler.post(() -> callback.onSynced(normalized, true));
                            return;
                        }
                    }

                    String geminiLrc = callFirebaseAiText(prompt);
                    if (geminiLrc != null && geminiLrc.contains("[")) {
                        String normalized = normalizeLrcUniversal(geminiLrc, durationMs);
                        mainHandler.post(() -> callback.onSynced(normalized, true));
                        return;
                    }
                }

                String offlineNormalized = normalizeLrcUniversal(rawLyrics, durationMs);
                mainHandler.post(() -> callback.onSynced(offlineNormalized, false));
            } finally {
                executor.shutdown();
            }
        });
    }

    /**
     * Universal offline LRC normalizer:
     * - Parses [m:ss], [mm:ss.xx], [mm:ss.xxx], [mm:ss:xx], multi-timestamp chorus lines, and [offset:+/-ms].
     * - Automatically rescales timestamps proportionally if the LRC was authored for a longer radio/extended edit.
     * - Generates smart vocal-window [mm:ss.xx] timestamps if plain text lyrics have no time tags.
     */
    public static String normalizeLrcUniversal(String rawLyrics, long trackDurationMs) {
        if (rawLyrics == null || rawLyrics.trim().isEmpty()) return "";

        String cleaned = rawLyrics
                .replaceAll("```[a-zA-Z]*", "")
                .replace("```", "")
                .trim();

        String[] lines = cleaned.split("\\r?\\n");
        long headerOffsetMs = 0L;

        class TimedEntry implements Comparable<TimedEntry> {
            long ms;
            final String text;

            TimedEntry(long ms, String text) {
                this.ms = ms;
                this.text = text;
            }

            @Override
            public int compareTo(TimedEntry o) {
                return Long.compare(this.ms, o.ms);
            }
        }

        List<TimedEntry> timed = new ArrayList<>();
        List<String> untimed = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            Matcher offM = OFFSET_TAG.matcher(line);
            if (offM.find()) {
                try {
                    String g = offM.group(1);
                    if (g != null) headerOffsetMs = Long.parseLong(g);
                } catch (Exception ignored) {
                }
                continue;
            }
            if (line.matches("(?i)^\\[(ti|ar|al|by|length|re|ve):.*]$")) {
                continue;
            }

            Matcher m = UNIVERSAL_LRC_TAG.matcher(line);
            List<Long> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                String gMin = m.group(1);
                String gSec = m.group(2);
                if (gMin == null || gSec == null) continue;
                long min = Long.parseLong(gMin);
                long sec = Long.parseLong(gSec);
                String frac = m.group(3);
                long subMs = 0L;
                if (frac != null && !frac.isEmpty()) {
                    if (frac.length() == 1) subMs = Long.parseLong(frac) * 100L;
                    else if (frac.length() == 2) subMs = Long.parseLong(frac) * 10L;
                    else subMs = Long.parseLong(frac.substring(0, 3));
                }
                stamps.add(min * 60_000L + sec * 1_000L + subMs);
                lastEnd = m.end();
            }

            if (!stamps.isEmpty()) {
                String lyric = line.substring(lastEnd).trim();
                if (!lyric.isEmpty()) {
                    for (Long t : stamps) {
                        timed.add(new TimedEntry(Math.max(0L, t - headerOffsetMs), lyric));
                    }
                }
            } else {
                untimed.add(line);
            }
        }

        if (!timed.isEmpty()) {
            Collections.sort(timed);
            long maxLrcTime = timed.get(timed.size() - 1).ms;
            if (trackDurationMs > 15_000L && maxLrcTime > trackDurationMs + 5_000L) {
                double scale = (double) (trackDurationMs - 3_000L) / (double) maxLrcTime;
                for (TimedEntry e : timed) {
                    e.ms = Math.max(0L, Math.round(e.ms * scale));
                }
            }
            StringBuilder out = new StringBuilder();
            for (TimedEntry e : timed) {
                out.append(formatLrcTimestamp(e.ms)).append(' ').append(e.text).append('\n');
            }
            return out.toString().trim();
        }

        if (!untimed.isEmpty()) {
            long effectiveDur = trackDurationMs > 20_000L ? trackDurationMs : 180_000L;
            long introLeadMs = Math.min(8_000L, effectiveDur / 12L);
            long outroTailMs = Math.min(8_000L, effectiveDur / 12L);
            long vocalWindow = Math.max(10_000L, effectiveDur - introLeadMs - outroTailMs);

            StringBuilder out = new StringBuilder();
            int n = untimed.size();
            for (int i = 0; i < n; i++) {
                long ts = introLeadMs + (vocalWindow * i) / n;
                out.append(formatLrcTimestamp(ts)).append(' ').append(untimed.get(i)).append('\n');
            }
            return out.toString().trim();
        }

        return cleaned;
    }

    private static String formatLrcTimestamp(long ms) {
        long safe = Math.max(0L, ms);
        long min = safe / 60_000L;
        long sec = (safe % 60_000L) / 1_000L;
        long cs = (safe % 1_000L) / 10L;
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, cs);
    }

    private static String callFirebaseAiText(String promptText) {
        try {
            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel("gemini-2.5-flash");
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);
            Content prompt = new Content.Builder().addText(promptText).build();
            ListenableFuture<GenerateContentResponse> future = model.generateContent(prompt);
            GenerateContentResponse result = future.get(12, TimeUnit.SECONDS);
            if (result != null && result.getText() != null) {
                return result.getText().trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String callOpenRouterText(String apiKey, String prompt) {
        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("model", "meta-llama/llama-3.3-70b-instruct:free");
            JSONArray messages = new JSONArray();
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            messages.put(msg);
            body.put("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() == 200) {
                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
                JSONObject jsonResp = new JSONObject(resp.toString());
                return jsonResp.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content").trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String[] callFirebaseAiSdk(String rawTitle, String rawArtist) {
        String promptText = "Extract the clean song title and artist from this messy filename/tag. "
                + "Raw Title: \"" + rawTitle + "\", Raw Artist: \"" + rawArtist + "\". "
                + "Respond ONLY with valid JSON in this exact format: {\"title\":\"Clean Title\",\"artist\":\"Clean Artist\"}";
        String res = callFirebaseAiText(promptText);
        return res != null ? parseJsonPair(res, rawTitle, rawArtist) : null;
    }

    private static String[] callOpenRouterModel(String apiKey, String rawTitle, String rawArtist) {
        String prompt = "Extract the clean song title and artist from this messy filename/tag. "
                + "Raw Title: \"" + rawTitle + "\", Raw Artist: \"" + rawArtist + "\". "
                + "Respond ONLY with valid JSON in this exact format: {\"title\":\"Clean Title\",\"artist\":\"Clean Artist\"}";
        String res = callOpenRouterText(apiKey, prompt);
        return res != null ? parseJsonPair(res, rawTitle, rawArtist) : null;
    }

    private static String[] parseJsonPair(String content, String rawTitle, String rawArtist) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                JSONObject parsed = new JSONObject(content.substring(start, end + 1));
                String aiTitle = parsed.optString("title", rawTitle).trim();
                String aiArtist = parsed.optString("artist", rawArtist).trim();
                return new String[]{aiTitle, aiArtist};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static List<Song> curateSmartMix(Context context, List<Song> allSongs) {
        List<Song> mix = new ArrayList<>();
        List<Song> others = new ArrayList<>();
        for (Song s : allSongs) {
            if (s.getUri() != null && SongMetadataManager.isFavorite(context, s.getUri().toString())) {
                mix.add(s);
            } else {
                others.add(s);
            }
        }
        Collections.shuffle(others);
        int limit = Math.min(25, others.size());
        for (int i = 0; i < limit; i++) {
            mix.add(others.get(i));
        }
        return mix.isEmpty() ? new ArrayList<>(allSongs) : mix;
    }

    private static String[] cleanOffline(String rawTitle, String rawArtist) {
        String title = rawTitle != null ? rawTitle : "Unknown Track";
        String artist = (rawArtist != null && !rawArtist.contains("<unknown>")) ? rawArtist : "Unknown Artist";

        title = title.replace("_", " ")
                .replaceAll("(?i)\\(official.*?\\)|\\[official.*?]|\\(lyrics.*?\\)|\\[lyrics.*?]|\\(audio\\)|\\[audio]|\\bHD\\b|\\bHQ\\b|\\b320kbps\\b|\\b128kbps\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            if (artist.equalsIgnoreCase("Unknown Artist") || artist.equalsIgnoreCase("Unknown")) {
                artist = parts[0].trim();
                title = parts[1].trim();
            }
        }
        return new String[]{title, artist};
    }
}
