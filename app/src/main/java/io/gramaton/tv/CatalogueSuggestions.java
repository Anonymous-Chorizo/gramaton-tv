package io.gramaton.tv;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

/** Runs the catalogue's typeahead request independently of the results WebView. */
final class CatalogueSuggestions {
    private static final String ORIGIN = "https://gramaton.io";
    private static final String ENDPOINT = ORIGIN + "/search/auto";
    private static final int DEBOUNCE_MS = 300;
    private static final int MAX_RESPONSE_CHARS = 32768;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final String userAgent;
    private Future<?> activeRequest;
    private int generation;

    CatalogueSuggestions(String userAgent) { this.userAgent = userAgent; }

    void lookup(String query, Consumer<List<JSONObject>> complete) {
        cancel();
        int request = generation;
        main.postDelayed(() -> {
            String url = Uri.parse(ENDPOINT).buildUpon()
                .appendQueryParameter("c", "no").appendQueryParameter("q", query).build().toString();
            // Use the existing signed-in session only for this request to Gramaton.
            String cookie = CookieManager.getInstance().getCookie(url);
            activeRequest = worker.submit(() -> {
                List<JSONObject> titles = fetch(url, cookie);
                main.post(() -> {
                    if (request != generation) return;
                    activeRequest = null;
                    complete.accept(titles);
                });
            });
        }, DEBOUNCE_MS);
    }

    // An empty list is a successful lookup with no matches. Null allows the local fallback.
    private List<JSONObject> fetch(String url, String cookie) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            connection.setRequestProperty("User-Agent", userAgent);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (connection.getResponseCode() != 200) return null;
            StringBuilder json = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    if (json.length() + read > MAX_RESPONSE_CHARS || Thread.currentThread().isInterrupted()) return null;
                    json.append(buffer, 0, read);
                }
            }
            JSONArray matches = new JSONArray(json.toString());
            LinkedHashMap<String, JSONObject> titles = new LinkedHashMap<>();
            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.optJSONObject(i);
                if (match == null || match.isNull("title")) continue;
                String title = Html.fromHtml(match.optString("title"), Html.FROM_HTML_MODE_LEGACY).toString().trim();
                if (title.isEmpty()) continue;
                String link = match.optString("link").trim();
                Uri page = Uri.parse(link.startsWith("/") && !link.startsWith("//") ? ORIGIN + link : link);
                if (!MainActivity.isMovie(page)) continue;
                String year = match.isNull("year") ? "" : match.optString("year").trim();
                String poster = match.isNull("poster") ? "" : match.optString("poster").trim();
                JSONObject card = new JSONObject()
                    .put("url", page.toString()).put("title", title).put("image", poster)
                    .put("year", year).put("meta", year).put("series", page.getPath().startsWith("/series/"));
                titles.putIfAbsent(page.getPath(), card);
            }
            return new ArrayList<>(titles.values());
        } catch (IOException | JSONException ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void cancel() {
        generation++;
        main.removeCallbacksAndMessages(null);
        if (activeRequest != null) activeRequest.cancel(true);
        activeRequest = null;
    }

    void close() {
        cancel();
        worker.shutdownNow();
    }
}
