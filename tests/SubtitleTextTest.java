package io.gramaton.tv;
import java.nio.charset.StandardCharsets;
public class SubtitleTextTest {
 static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
 public static void main(String[] args) {
  check(SubtitleText.sameTitle("Spider-Man", "Spider Man"), "title punctuation");
  check(!SubtitleText.sameTitle("The Matrix", "The Matrix Reloaded"), "different movie");
  check(!SubtitleText.compatibleYear("1999", "The.Matrix.2021.1080p"), "different year");
  check(SubtitleText.compatibleYear("1999", "The.Matrix.1999.BluRay"), "correct year");
  check(SubtitleText.sameEpisode("Show.S01E02.1080p",1,2), "correct episode");
  check(!SubtitleText.sameEpisode("Show.S01E020",1,2), "different episode");
  check(!SubtitleText.sameEpisode("Show.S02E02",1,2), "different season");
  String srt="1\r\n00:00:01,000 --> 00:00:03,000\r\nHello\r\n";
  check(SubtitleText.validSrt(SubtitleText.decode(srt.getBytes(StandardCharsets.UTF_16))), "UTF16 provider download");
  check(SubtitleText.validSrt(SubtitleText.decode(srt.getBytes(StandardCharsets.UTF_8))), "UTF8 download");
  check(!SubtitleText.validSrt("<html>Service unavailable</html>"), "HTML rejection");
  System.out.println("10 subtitle matching/encoding checks passed");
 }
}
