package io.gramaton.tv;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.*;

final class SubtitleText {
    private static String normalized(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }
    static boolean sameTitle(String a, String b) { return !normalized(a).isEmpty() && normalized(a).equals(normalized(b)); }
    static boolean compatibleYear(String year, String release) {
        if (year.isEmpty()) return true;
        String releaseYear = yearIn(release);
        return releaseYear.isEmpty() || year.equals(releaseYear);
    }
    static String yearIn(String text) {
        Matcher match = Pattern.compile("(?<![0-9])(?:18|19|20)[0-9]{2}(?![0-9])").matcher(text);
        return match.find() ? match.group() : "";
    }
    static boolean isEpisode(String release) {
        return Pattern.compile("(?i)(?:s[0-9]{1,3}e[0-9]{1,4}|(?<![0-9])[0-9]{1,2}x[0-9]{1,3}(?![0-9]))").matcher(release).find();
    }
    static boolean sameEpisode(String release, int season, int episode) {
        return Pattern.compile(String.format(Locale.US, "(?i)(?:s0*%de0*%d|0*%dx0*%d)(?!\\d)", season, episode, season, episode)).matcher(release).find();
    }
    static boolean plausibleTimeline(long firstMs, long lastMs, long durationMs) {
        if (firstMs < 0 || lastMs <= firstMs) return false;
        if (durationMs <= 0) return true;
        long allowance = Math.max(120_000, durationMs / 8);
        return firstMs < durationMs && lastMs >= durationMs * .75 && lastMs <= durationMs + allowance;
    }

    static String decode(byte[] bytes) {
        String text;
        if (bytes.length >= 2 && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254 || (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)) text = new String(bytes, StandardCharsets.UTF_16);
        else text = new String(bytes, StandardCharsets.UTF_8);
        return text.replace("\ufeff", "").replace("\r\n", "\n");
    }
    static boolean validSrt(String text) {
        return !text.contains("\u0000") && !text.trim().startsWith("<") && Pattern.compile("(?m)^\\d{2,}:\\d{2}:\\d{2},\\d{3}\\s+-->\\s+\\d{2,}:\\d{2}:\\d{2},\\d{3}").matcher(text).find();
    }
}
