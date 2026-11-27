package io.gramaton.tv;

public final class PlaybackTimelineTest {
    private static int checks;
    private static void eq(Object expected,Object actual) { checks++;if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual); }
    public static void main(String[] args) {
        eq(0L,PlaybackTimeline.offset(2000,-10000,100000));
        eq(100000L,PlaybackTimeline.offset(95000,10000,100000));
        eq(Long.MAX_VALUE,PlaybackTimeline.offset(Long.MAX_VALUE-100,10000,Long.MAX_VALUE));
        eq(0L,PlaybackTimeline.offset(10000,10000,-9223372036854775807L));
        eq(10000L,PlaybackTimeline.step(0));eq(30000L,PlaybackTimeline.step(5));eq(60000L,PlaybackTimeline.step(15));
        PlaybackTimeline seek=new PlaybackTimeline(90000,100000,true);
        eq(100000L,seek.move(30000));eq(90000L,seek.original());eq(true,seek.wasPlaying);
        eq(0L,seek.select(-1));eq(90000L,seek.original());
        PlaybackTimeline paused=new PlaybackTimeline(5000,100000,false);eq(false,paused.wasPlaying);eq(15000L,paused.move(10000));
        eq("0:00",PlaybackTimeline.time(-1));eq("1:05",PlaybackTimeline.time(65000));eq("1:20:53",PlaybackTimeline.time(4853000));
        System.out.println(checks+" timeline checks passed");
    }
}
