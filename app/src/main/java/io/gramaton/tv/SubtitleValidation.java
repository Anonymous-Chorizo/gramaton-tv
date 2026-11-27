package io.gramaton.tv;

import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.Subtitle;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import java.util.concurrent.TimeUnit;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SubtitleValidation {
    static boolean isEnglishForPlayback(Subtitle subtitle, long videoDurationMs) throws Exception {
        int events = subtitle.getEventTimeCount();
        if (events < 2 || !SubtitleText.plausibleTimeline(
                subtitle.getEventTime(0) / 1000,
                subtitle.getEventTime(events - 1) / 1000, videoDurationMs)) return false;

        // Sample actual dialogue from across the track so a provider's language
        // label, credits, or a single English line cannot decide the result.
        StringBuilder sample = new StringBuilder();
        for (int section = 0; section < 3; section++) {
            int start = events * section / 3;
            int end = events * (section + 1) / 3;
            int limit = sample.length() + 850;
            for (int i = start; i < end && sample.length() < limit; i++) {
                for (Cue cue : subtitle.getCues(subtitle.getEventTime(i))) {
                    if (cue.text != null) {
                        int length = Math.min(cue.text.length(), limit - sample.length());
                        if (length <= 0) break;
                        sample.append(cue.text, 0, length).append(' ');
                    }
                }
            }
        }
        if (sample.toString().trim().length() < 40) return false;
        LanguageIdentifier identifier = LanguageIdentification.getClient(
            new LanguageIdentificationOptions.Builder().setConfidenceThreshold(.75f).build());
        try {
            return "en".equals(Tasks.await(identifier.identifyLanguage(sample.toString()), 5, TimeUnit.SECONDS));
        } finally {
            identifier.close();
        }
    }
}
