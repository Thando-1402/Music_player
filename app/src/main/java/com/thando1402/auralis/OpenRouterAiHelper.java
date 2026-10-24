package com.thando1402.auralis;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenRouterAiHelper {

    private static final String PREFS_NAME = "auralis_settings";
    private static final String KEY_OPENROUTER_API = "openrouter_api_key";
    private static final String KEY_PROFILE_NAME = "profile_display_name";

    public interface MetadataCallback {
        void onResult(String cleanTitle, String cleanArtist, boolean usedCloudAi);
    }

    public static void saveApiKey(Context context, String apiKey) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_OPENROUTER_API, apiKey != null ? apiKey.trim() : "").apply();
    }

    public static String getApiKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String manualKey = prefs.getString(KEY_OPENROUTER_API, "");
        if (manualKey != null && !manualKey.trim().isEmpty()) {
            return manualKey.trim();
        }
        try {
            String buildKey = context.getString(R.string.default_openrouter_key);
            if (buildKey != null && !buildKey.trim().isEmpty()) {
                return buildKey.trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static boolean hasApiKey(Context context) {
        String key = getApiKey(context);
        return key != null && !key.trim().isEmpty();
    }

    public static void setProfileName(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PROFILE_NAME, name != null && !name.trim().isEmpty() ? name.trim() : "Alex Vance").apply();
    }

    public static String getProfileName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PROFILE_NAME, "Alex Vance");
    }

    public static void autoCleanSongMetadata(Context context, String rawTitle, String rawArtist, MetadataCallback callback) {
        final String apiKey = getApiKey(context);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                if (apiKey != null && !apiKey.isEmpty()) {
                    try {
                        URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(6000);
                        conn.setReadTimeout(8000);
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

                            int start = content.indexOf("{");
                            int end = content.lastIndexOf("}");
                            if (start != -1 && end != -1 && end > start) {
                                JSONObject parsed = new JSONObject(content.substring(start, end + 1));
                                final String aiTitle = parsed.optString("title", rawTitle);
                                final String aiArtist = parsed.optString("artist", rawArtist);
                                mainHandler.post(() -> callback.onResult(aiTitle, aiArtist, true));
                                return;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                String[] offline = cleanOffline(rawTitle, rawArtist);
                mainHandler.post(() -> callback.onResult(offline[0], offline[1], false));
            } finally {
                executor.shutdown();
            }
        });
    }

    private static String[] cleanOffline(String rawTitle, String rawArtist) {
        String title = rawTitle != null ? rawTitle : "Unknown Track";
        String artist = (rawArtist != null && !rawArtist.contains("<unknown>")) ? rawArtist : "Unknown Artist";

        title = title.replace("_", " ")
                .replaceAll("(?i)\\(official.*?\\)|\\[official.*?\\]|\\(lyrics.*?\\)|\\[lyrics.*?\\]|\\(audio\\)|\\[audio\\]|\\bHD\\b|\\bHQ\\b|\\b320kbps\\b", "")
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