package io.gramaton.tv;

import android.content.Context;
import androidx.media3.extractor.text.Subtitle;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.subrip.SubripParser;
import androidx.media3.extractor.text.webvtt.WebvttParser;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SubtitleProvider {
    private static final String ORIGIN = "https://subtitles.website";
    private static final String METADATA = "https://v3-cinemeta.stremio.io";
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static Subtitle find(Context context, JSONObject movie, int season, int episode, String key, long durationMs) throws Exception {
        File target = cacheFile(context, key);
        if (target.isFile() && target.length() <= MAX_BYTES) {
            try (InputStream input = new FileInputStream(target)) {
                Subtitle cached = parse(SubtitleText.decode(read(input)));
                if (SubtitleValidation.isEnglishForPlayback(cached, durationMs)) return cached;
                target.delete();
            } catch (IOException | RuntimeException ignored) {
                target.delete();
            }
        }

        String title = movie.optString("title").trim();
        boolean series = movie.optBoolean("series");
        if (title.isEmpty() || (series && (season <= 0 || episode <= 0))) return null;
        String year = movie.optString("subtitleYear");
        if (year.isEmpty()) year = SubtitleText.yearIn(movie.optString("meta"));
        String query = title + (series ? String.format(Locale.US, " S%02dE%02d", season, episode) : "");
        String imdb = movie.optString("subtitleImdbId");
        if (!imdb.matches("tt[0-9]+")) imdb = "";
        String type = series ? "series" : "movie";

        // A title alone cannot distinguish remakes. Resolve it first when
        // the movie page has neither a release year nor an IMDb ID.
        if (!series && imdb.isEmpty() && year.isEmpty()) {
            TitleIdentity identity = resolveImdb(type, title, year);
            if (identity == null) return null;
            imdb = identity.imdb;
            year = identity.year;
        }
        if (!imdb.isEmpty()) {
            Subtitle byId = downloadById(type, imdb, season, episode, target, durationMs);
            if (byId != null) return byId;
        }
        if (!series && year.isEmpty()) return null;
        JSONArray results = json(ORIGIN + "/strapi/api/search/subtitles?format=srt&language=en&title=" + encode(query)).optJSONArray("data");
        List<JSONObject> matches = new ArrayList<>();
        if (results != null) for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            JSONObject language = item.optJSONObject("language");
            String release = item.optString("release");
            if (language == null || !isEnglish(language.optString("code"))
                    || !"srt".equalsIgnoreCase(item.optString("format"))
                    || !SubtitleText.sameTitle(title, item.optString("film_title"))
                    || !item.optString("id").matches("[A-Za-z0-9_-]+")) continue;
            if (series ? !SubtitleText.sameEpisode(release, season, episode)
                    : SubtitleText.isEpisode(release) || !year.equals(SubtitleText.yearIn(release))) continue;
            matches.add(item);
        }
        final String expectedYear = year;
        matches.sort(Comparator.<JSONObject>comparingInt(item ->
            !expectedYear.isEmpty() && expectedYear.equals(SubtitleText.yearIn(item.optString("release"))) ? 0 : 1)
            .thenComparing(Comparator.comparingInt((JSONObject item) -> item.optInt("downloads")).reversed()));
        for (int i = 0; i < Math.min(3, matches.size()); i++) {
            JSONObject response;
            try {
                response = json(ORIGIN + "/strapi/api/search/download/" + matches.get(i).getString("id"));
            } catch (MissingFile unavailable) {
                continue;
            }
            String path = response.optString("download_url");
            if (!path.startsWith("/uploads/") || path.contains("..")) continue;
            Subtitle result = download(ORIGIN + "/strapi" + path, target, durationMs);
            if (result != null) return result;
        }
        if (!imdb.isEmpty()) return null;
        TitleIdentity resolved = resolveImdb(type, title, year);
        return resolved == null ? null : downloadById(type, resolved.imdb, season, episode, target, durationMs);
    }

    private static TitleIdentity resolveImdb(String type, String title, String year) throws Exception {
        JSONArray results;
        try {
            results = json(METADATA + "/catalog/" + type + "/top/search=" + encode(title) + ".json").optJSONArray("metas");
        } catch (MissingFile unavailable) {
            return null;
        }
        TitleIdentity found = null;
        if (results != null) for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null || !SubtitleText.sameTitle(title, item.optString("name"))) continue;
            String releaseYear = SubtitleText.yearIn(item.optString("releaseInfo"));
            if (!year.isEmpty() && !year.equals(releaseYear)) continue;
            String id = item.optString("id");
            if (!id.matches("tt[0-9]+")) continue;
            if (found != null && !found.imdb.equals(id)) return null;
            found = new TitleIdentity(id, releaseYear);
        }
        return found;
    }

    private static Subtitle downloadById(String type, String imdb, int season, int episode, File target,
            long durationMs) throws Exception {
        String id = "series".equals(type) ? imdb + ":" + season + ":" + episode : imdb;
        JSONArray results;
        try {
            results = json(ORIGIN + "/stremio/subtitles/" + type + "/" + id + ".json").optJSONArray("subtitles");
        } catch (MissingFile unavailable) {
            return null;
        }
        int attempts = 0;
        if (results != null) for (int i = 0; i < results.length() && attempts < 3; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null || !isEnglish(item.optString("lang"))) continue;
            String url = item.optString("url");
            try {
                if (!trusted(new URL(url), false)) continue;
            } catch (MalformedURLException invalid) {
                continue;
            }
            attempts++;
            Subtitle subtitle = download(url, target, durationMs);
            if (subtitle != null) return subtitle;
        }
        return null;
    }

    private static final class TitleIdentity {
        final String imdb;
        final String year;

        TitleIdentity(String imdb, String year) {
            this.imdb = imdb;
            this.year = year;
        }
    }

    private static boolean isEnglish(String language) {
        return "en".equalsIgnoreCase(language) || "eng".equalsIgnoreCase(language);
    }

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static File cacheFile(Context context, String key) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : hash) name.append(String.format(Locale.US, "%02x", value & 255));
        File directory = new File(context.getCacheDir(), "english-captions-checked");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Couldn’t open the subtitle cache.");
        return new File(directory, name + ".txt");
    }

    private static Subtitle download(String url, File target, long durationMs) throws Exception {
        String text;
        try {
            text = SubtitleText.decode(request(url));
        } catch (MissingFile unavailable) {
            return null;
        }
        Subtitle subtitle;
        try {
            subtitle = parse(text);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
        if (!SubtitleValidation.isEnglishForPlayback(subtitle, durationMs)) return null;
        File temp = File.createTempFile("subtitle-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(target)) throw new IOException("Couldn’t save subtitles.");
        } finally {
            temp.delete();
        }
        return subtitle;
    }

    static Subtitle parse(String text) throws IOException {
        SubtitleParser parser;
        if (text.startsWith("WEBVTT")) parser = new WebvttParser();
        else if (SubtitleText.validSrt(text)) parser = new SubripParser();
        else throw new IOException("Unsupported subtitle file.");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Subtitle subtitle = parser.parseToLegacySubtitle(bytes, 0, bytes.length);
        if (subtitle.getEventTimeCount() == 0) throw new IOException("Subtitle file has no captions.");
        return subtitle;
    }

    private static JSONObject json(String url) throws Exception {
        return new JSONObject(new String(request(url), StandardCharsets.UTF_8));
    }

    private static boolean trusted(URL url, boolean metadata) {
        String host = url.getHost();
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null
            && (url.getPort() == -1 || url.getPort() == 443)
            && (metadata ? "v3-cinemeta.strem.io".equals(host) : "subtitles.website".equals(host) || "www.subtitles.website".equals(host));
    }

    private static byte[] request(String address) throws IOException {
        URL url = new URL(address);
        boolean metadata = "v3-cinemeta.strem.io".equals(url.getHost());
        for (int redirects = 0; redirects < 4; redirects++) {
            if (!trusted(url, metadata)) throw new IOException("Unexpected subtitle provider address.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "GramatonTV");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid subtitle redirect.");
                    url = new URL(url, location);
                    continue;
                }
                if (status == 429) throw new QuotaExceeded();
                if (status == 404) throw new MissingFile();
                if (status != 200) throw new IOException("Subtitle service HTTP " + status);
                try (InputStream input = connection.getInputStream()) {
                    return read(input);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many subtitle redirects.");
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (output.size() + count > MAX_BYTES) throw new IOException("Subtitle response is too large.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static final class QuotaExceeded extends IOException {}
    private static final class MissingFile extends IOException {}
}
