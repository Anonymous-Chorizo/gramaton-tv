package io.gramaton.tv;

import java.util.Locale;

/** Pure seek math shared by remote and pointer input. Candidate times never seek the player. */
final class PlaybackTimeline {
    static long clamp(long position, long duration) { return Math.max(0, Math.min(position, Math.max(0, duration))); }
    static long offset(long position, long delta, long duration) {
        long start = clamp(position, duration);
        if (delta > 0 && delta > Long.MAX_VALUE - start) return Math.max(0, duration);
        return clamp(start + delta, duration);
    }
    static long step(int repeats) { return repeats < 5 ? 10000 : repeats < 15 ? 30000 : 60000; }
    static String time(long millis) {
        long s = Math.max(0, millis / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
    private final long origin, duration;
    final boolean wasPlaying;
    private long candidate;
    PlaybackTimeline(long position, long duration, boolean wasPlaying) {
        this.duration = Math.max(0, duration); origin = clamp(position, duration); candidate = origin; this.wasPlaying = wasPlaying;
    }
    long move(long delta) { candidate = offset(candidate, delta, duration); return candidate; }
    long select(long position) { candidate = clamp(position, duration); return candidate; }
    long candidate() { return candidate; }
    long original() { return origin; }
}
