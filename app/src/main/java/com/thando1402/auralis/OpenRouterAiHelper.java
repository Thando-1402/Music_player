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
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OpenRouterAiHelper {

    private static final String PREFS_NAME = "auralis_settings";
    private static final String KEY_OPENROUTER_API = "openrouter_api_key";
    private static final String KEY_PROFILE_NAME = "profile_display_name";
    private static boolean appCheckInitialized = false;

    public interface MetadataCallback {
        void onResult(String cleanTitle, String cleanArtist, boolean usedCloudAi);
    }

    public static synchronized void initFirebaseAppCheck(Context context) {
        if (appCheckInitialized) return;
        try {
            FirebaseApp.initializeApp(context.getApplicationContext());
            FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance();
            firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance());
            appCheckInitialized = true;
        } catch (Exception ignored) {
        }
    }

    public static void saveApiKey(Context context, String apiKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_OPENROUTER_API, apiKey != null ? apiKey.trim() : "").apply();
    }

    public static String getApiKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String manualKey = prefs.getString(KEY_OPENROUTER_API, "");
        if (!manualKey.trim().isEmpty()) {
            return manualKey.trim();
        }
        try {
            String buildKey = context.getString(R.string.default_openrouter_key);
            if (!buildKey.trim().isEmpty()) {
                return buildKey.trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static boolean hasApiKey(Context context) {
        return !getApiKey(context).isEmpty() || appCheckInitialized;
    }

    public static void setProfileName(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PROFILE_NAME, name != null && !name.trim().isEmpty() ? name.trim() : "Alex Vance").apply();
    }

    public static String getProfileName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PROFILE_NAME, "Alex Vance");
    }

    /**
     * 3-Tier Dual-AI Failover Router:
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

    private static String[] callFirebaseAiSdk(String rawTitle, String rawArtist) {
        try {
            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel("gemini-2.5-flash");
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);

            String promptText = "Extract the clean song title and artist from this messy filename/tag. "
                    + "Raw Title: \"" + rawTitle + "\", Raw Artist: \"" + rawArtist + "\". "
                    + "Respond ONLY with valid JSON in this exact format: {\"title\":\"Clean Title\",\"artist\":\"Clean Artist\"}";

            Content prompt = new Content.Builder()
                    .addText(promptText)
                    .build();

            ListenableFuture<GenerateContentResponse> future = model.generateContent(prompt);
            GenerateContentResponse result = future.get(8, TimeUnit.SECONDS);
            if (result != null && result.getText() != null) {
                return parseJsonPair(result.getText().trim(), rawTitle, rawArtist);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String[] callOpenRouterModel(String apiKey, String rawTitle, String rawArtist) {
        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String prompt = "Extract the clean song title and artist from this messy filename/tag. "
                    + "Raw Title: \"" + rawTitle + "\", Raw Artist: \"" + rawArtist + "\". "
                    + "Respond ONLY with valid JSON in this exact format: {\"title\":\"Clean Title\",\"artist\":\"Clean Artist\"}";

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
                String content = jsonResp.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content").trim();

                return parseJsonPair(content, rawTitle, rawArtist);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String[] parseJsonPair(String content, String rawTitle, String rawArtist) {
        try {
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
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
                .replaceAll("(?i)\\(official.*?\\)|\\[official.*?\\]|\\(lyrics.*?\\)|\\[lyrics.*?\\]|\\(audio\\)|\\[audio\\]|\\bHD\\b|\\bHQ\\b|\\b320kbps\\b|\\b128kbps\\b", "")
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