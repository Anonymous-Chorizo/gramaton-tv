package io.gramaton.tv;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public guest API; never receives Gramaton cookies, credentials or media URLs. */
final class SubtitleProvider {
    private static final String ORIGIN = "https://subtitles.website";
    static final class Match {
        final String id, label;
        final int downloads;
        Match(String id, String label, int downloads) { this.id = id; this.label = label; this.downloads = downloads; }
    }
    static List<Match> search(JSONObject movie, int season, int episode) throws Exception {
        String title = movie.optString("title").trim();
        boolean series = movie.optBoolean("series");
        if (series && (season <= 0 || episode <= 0)) throw new IOException("Choose a season and episode first.");
        String query = title + (series ? String.format(Locale.US, " S%02dE%02d", season, episode) : "");
        String url = ORIGIN + "/strapi/api/search/subtitles?format=srt&language=en&title=" + URLEncoder.encode(query, "UTF-8");
        JSONArray data = json(url).optJSONArray("data");
        List<Match> matches = new ArrayList<>();
        java.util.regex.Matcher yearMatch = java.util.regex.Pattern.compile("\\b(?:19|20)\\d{2}\\b").matcher(movie.optString("meta"));
        String year = yearMatch.find() ? yearMatch.group() : "";
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String release = item.optString("release");
            String film = item.optString("film_title");
            JSONObject language = item.optJSONObject("language");
            if (language == null || !"en".equals(language.optString("code")) || !"srt".equalsIgnoreCase(item.optString("format"))) continue;
            if (!SubtitleText.sameTitle(title, film)) continue;
            if (!series && !SubtitleText.compatibleYear(year, release)) continue;
            if (series && !SubtitleText.sameEpisode(release, season, episode)) continue;
            String id = item.optString("id"); if (!id.matches("[A-Za-z0-9_-]+")) continue;
            matches.add(new Match(id, release.isEmpty() ? film : release, item.optInt("downloads")));
            if (matches.size() >= 30) break;
        }
        matches.sort((a,b) -> Integer.compare(b.downloads, a.downloads));
        return matches;
    }
    static File cached(Context context, String movieKey) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(movieKey.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(); for (byte b : hash) name.append(String.format(Locale.US, "%02x", b & 255));
        File directory = new File(context.getCacheDir(), "english-subtitles");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create subtitle cache.");
        return new File(directory, name + ".srt");
    }
    static File download(Context context, Match match, String movieKey) throws Exception {
        JSONObject response = json(ORIGIN + "/strapi/api/search/download/" + match.id);
        String path = response.optString("download_url");
        if (!path.startsWith("/uploads/") || path.contains("..")) throw new IOException("The subtitle provider returned an invalid download.");
        byte[] bytes = request(ORIGIN + "/strapi" + path, 2 * 1024 * 1024);
        String srt = SubtitleText.decode(bytes);
        if (!SubtitleText.validSrt(srt)) throw new IOException("The downloaded file was not a supported subtitle file.");
        File target = cached(context, movieKey), temp = File.createTempFile("subtitle-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(srt.getBytes(StandardCharsets.UTF_8)); }
            if (!temp.renameTo(target)) throw new IOException("Could not save subtitles.");
        } finally { temp.delete(); }
        return target;
    }
    private static JSONObject json(String url) throws Exception { return new JSONObject(new String(request(url, 2 * 1024 * 1024), StandardCharsets.UTF_8)); }
    private static byte[] request(String url, int limit) throws Exception {
        for (int redirects = 0; redirects < 4; redirects++) {
            URL target = new URL(url);
            if (!"https".equals(target.getProtocol()) || !("subtitles.website".equals(target.getHost()) || "www.subtitles.website".equals(target.getHost()))) throw new IOException("Invalid subtitle provider address.");
            HttpURLConnection conn = (HttpURLConnection)target.openConnection();
            conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "GramatonTV/0.12");
            try {
                int status = conn.getResponseCode();
                if (status >= 300 && status < 400) { String location = conn.getHeaderField("Location"); if (location == null) throw new IOException("Invalid subtitle redirect."); url = new URL(target, location).toString(); continue; }
                if (status == 429) throw new IOException("The free subtitle service’s daily limit has been reached. Try again tomorrow.");
                if (status != 200) throw new IOException("Subtitle service unavailable (HTTP " + status + "). Try again later.");
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("Subtitle response was too large."); out.write(buffer, 0, n); }
                    return out.toByteArray();
                }
            } finally { conn.disconnect(); }
        }
        throw new IOException("Too many subtitle service redirects.");
    }
}
