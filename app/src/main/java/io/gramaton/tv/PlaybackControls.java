package io.gramaton.tv;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.Subtitle;
import androidx.media3.ui.SubtitleView;
import androidx.media3.common.*;
import androidx.media3.ui.PlayerView;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.*;

/** Fire TV remote controls with a quiet bottom overlay and cancellable timeline scrubbing. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PlaybackControls extends FrameLayout {
    interface Actions { void findSubtitles(); }
    private final Activity activity;
    private final PlayerView video;
    private final SubtitleView downloadedCaptions;
    private Subtitle externalSubtitle;
    private List<Cue> displayedCues = java.util.Collections.emptyList();
    private boolean externalEnabled;
    private boolean awaitingFirstCaption;
    private final Actions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout chrome, buttons, menu;
    private final FrameLayout panel;
    private final TextView elapsed, remaining, state, seekHint, endTime, feedback, menuTitle;
    private final SeekBar timeline;
    private final ProgressBar buffering;
    private final List<Button> transport = new ArrayList<>();
    private final Button playPause, captions;
    private ExoPlayer player;
    private PlaybackTimeline seek;
    private boolean visible = true, pointerSeeking, disposed;
    private static final int WHITE = 0xfffafafa, MUTED = 0xffc7c7c7;

    PlaybackControls(Activity activity, PlayerView video, String title, String metadata, Actions actions) {
        super(activity); this.activity = activity; this.video = video; this.actions = actions;
        setFocusable(true); setFocusableInTouchMode(true); setClipChildren(false);
        downloadedCaptions = new SubtitleView(activity);
        downloadedCaptions.setUserDefaultStyle();
        downloadedCaptions.setUserDefaultTextSize();
        downloadedCaptions.setVisibility(GONE);
        addView(downloadedCaptions, new FrameLayout.LayoutParams(-1, -1));
        chrome = new LinearLayout(activity); chrome.setOrientation(LinearLayout.VERTICAL); chrome.setPadding(dp(48), dp(40), dp(48), dp(28));
        chrome.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000,0xb8000000,0xf0000000}));
        addView(chrome, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.BOTTOM);
        LinearLayout names = new LinearLayout(activity); names.setOrientation(LinearLayout.VERTICAL);
        state = text("", 11, MUTED); state.setVisibility(View.GONE); state.setLetterSpacing(.12f); names.addView(state);
        TextView name = text(title, 25, WHITE); name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(name);
        if (metadata != null && !metadata.trim().isEmpty()) {
            TextView meta = text(metadata, 12, MUTED);
            meta.setMaxLines(1);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            names.addView(meta);
        }
        heading.addView(names, new LinearLayout.LayoutParams(0,-2,1));
        chrome.addView(heading);
        seekHint = text("", 12, WHITE); seekHint.setGravity(Gravity.CENTER); seekHint.setPadding(0,dp(3),0,0); chrome.addView(seekHint,new LinearLayout.LayoutParams(-1,dp(23)));
        timeline = new SeekBar(activity); timeline.setMax(10000); timeline.setPadding(dp(4),0,dp(4),0);
        timeline.setProgressTintList(ColorStateList.valueOf(WHITE)); timeline.setProgressBackgroundTintList(ColorStateList.valueOf(0x55ffffff)); timeline.setSecondaryProgressTintList(ColorStateList.valueOf(0x99ffffff)); timeline.setThumbTintList(ColorStateList.valueOf(WHITE)); timeline.setContentDescription("Playback timeline. Left or right to preview a time, Select to seek, Back to cancel.");
        chrome.addView(timeline,new LinearLayout.LayoutParams(-1,dp(27)));
        timeline.setOnFocusChangeListener((v, focus) -> { timeline.setAlpha(focus ? 1 : .8f); scheduleHide(); });
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { pointerSeeking = beginSeek(); }
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) { if(fromUser && seek != null) { seek.select((long)(progress / 10000d * duration())); update(); } }
            @Override public void onStopTrackingTouch(SeekBar bar) { if(pointerSeeking) commitSeek(); pointerSeeking = false; }
        });
        LinearLayout times = new LinearLayout(activity);
        times.setPadding(dp(4), 0, dp(4), 0);
        elapsed = text("0:00", 12, MUTED);
        remaining = text("", 12, MUTED);
        endTime = text("", 12, MUTED);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams endTimeSize = new LinearLayout.LayoutParams(-2, -2);
        endTimeSize.rightMargin = dp(12);
        times.addView(endTime, endTimeSize);
        times.addView(remaining);
        chrome.addView(times);
        buttons = new LinearLayout(activity); buttons.setGravity(Gravity.CENTER_VERTICAL); LinearLayout.LayoutParams buttonRow = new LinearLayout.LayoutParams(-1,dp(48)); buttonRow.topMargin=dp(8); chrome.addView(buttons,buttonRow);
        addControl("↶ 10", "Skip back 10 seconds", () -> skip(-10000));
        playPause = addControl("Ⅱ", "Pause", this::toggle);
        int diameter = dp(38);
        playPause.getLayoutParams().width = diameter;
        playPause.getLayoutParams().height = diameter;
        playPause.setPadding(0, 0, 0, 0);
        playPause.setGravity(Gravity.CENTER);
        playPause.setStateListAnimator(null);
        playPause.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(focused ? WHITE : 0x553d3d3d);
            circle.setStroke(dp(1), focused ? WHITE : 0x22ffffff);
            playPause.setBackground(circle);
            playPause.setTextColor(focused ? TvColors.BLACK : WHITE);
            scheduleHide();
        });
        playPause.getOnFocusChangeListener().onFocusChange(playPause, false);
        addControl("10 ↷", "Skip forward 10 seconds", () -> skip(10000));
        buttons.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        addControl("Audio", "Audio options", () -> showTracks(C.TRACK_TYPE_AUDIO));
        captions = addControl("CC", "Subtitles", () -> showTracks(C.TRACK_TYPE_TEXT));
        panel = new FrameLayout(activity); panel.setVisibility(GONE);
        FrameLayout.LayoutParams panelSize=new FrameLayout.LayoutParams(dp(330),dp(270),Gravity.RIGHT|Gravity.BOTTOM); panelSize.rightMargin=dp(48);panelSize.bottomMargin=dp(190);addView(panel,panelSize);
        LinearLayout panelBody=new LinearLayout(activity);panelBody.setOrientation(LinearLayout.VERTICAL);panelBody.setPadding(dp(16),dp(16),dp(16),dp(12));panelBody.setBackground(pill(0xf0282828,20,0x44ffffff));panel.addView(panelBody,new FrameLayout.LayoutParams(-1,-1));
        menuTitle=text("",19,WHITE); menuTitle.setPadding(dp(8),0,0,dp(10));panelBody.addView(menuTitle);
        ScrollView scroll=new ScrollView(activity);scroll.setFocusable(false);scroll.setVerticalScrollBarEnabled(false);menu=new LinearLayout(activity);menu.setOrientation(LinearLayout.VERTICAL);scroll.addView(menu);panelBody.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        buffering = new ProgressBar(activity); buffering.setIndeterminateTintList(ColorStateList.valueOf(WHITE));buffering.setVisibility(GONE);addView(buffering,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
        feedback=text("",16,WHITE);feedback.setGravity(Gravity.CENTER);feedback.setPadding(dp(18),dp(10),dp(18),dp(10));feedback.setBackground(pill(0xcf282828,18,0x33ffffff));feedback.setVisibility(GONE);FrameLayout.LayoutParams feedbackSize=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL|Gravity.TOP);feedbackSize.topMargin=dp(48);addView(feedback,feedbackSize);
        setOnClickListener(v -> { if(visible) hide(); else show(); });
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private GradientDrawable pill(int fill,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private Button control(String label,Runnable action){
        Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(WHITE);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinimumHeight(0);b.setPadding(dp(16),0,dp(16),0);b.setBackground(pill(0x553d3d3d,19,0x22ffffff));b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener((v,focus)->{b.setTextColor(focus?TvColors.BLACK:WHITE);b.setBackground(pill(focus?WHITE:0x553d3d3d,19,focus?0:0x22ffffff));scheduleHide();});b.setOnClickListener(v->{action.run();scheduleHide();});return b;
    }
    private Button addControl(String label,String accessibility,Runnable action){Button b=control(label,action);b.setContentDescription(accessibility);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(38));lp.rightMargin=dp(8);buttons.addView(b,lp);transport.add(b);return b;}
    void bind(ExoPlayer next){
        if(player!=null)player.removeListener(listener);player=next;seek=null;pointerSeeking=false;closePanel();
        if(next!=null){next.addListener(listener);show();handler.removeCallbacks(pulse);handler.post(pulse);}
        refreshExternalCaptions();
    }
    private final Player.Listener listener=new Player.Listener(){
        @Override public void onEvents(Player p,Player.Events events){refreshExternalCaptions();update();if(!p.getPlayWhenReady()||p.getPlaybackState()==Player.STATE_ENDED)show();else scheduleHide();}
    };
    private final Runnable pulse=new Runnable(){@Override public void run(){if(disposed||player==null)return;update();handler.postDelayed(this,500);}};
    private final Runnable hideAction=this::hide;
    private void clearFeedback(){feedback.setVisibility(GONE);}
    private final Runnable clearFeedback=this::clearFeedback;
    private void scheduleHide(){handler.removeCallbacks(hideAction);if(player!=null&&player.isPlaying()&&seek==null&&panel.getVisibility()!=VISIBLE)handler.postDelayed(hideAction,4000);}
    private void show(){visible=true;chrome.setVisibility(VISIBLE);if(video.getSubtitleView()!=null)video.getSubtitleView().setBottomPaddingFraction(.30f);if(!chrome.hasFocus()&&panel.getVisibility()!=VISIBLE)timeline.requestFocus();update();scheduleHide();}
    private void hide(){if(seek!=null||panel.getVisibility()==VISIBLE)return;visible=false;chrome.setVisibility(GONE);if(video.getSubtitleView()!=null)video.getSubtitleView().setBottomPaddingFraction(.08f);requestFocus();handler.removeCallbacks(hideAction);}
    private long duration(){return player==null?0:Math.max(0,player.getDuration());}
    private boolean seekable(){return player!=null&&duration()>0&&player.isCurrentMediaItemSeekable()&&player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);}
    private void update(){
        if(player==null)return;updateCaptionPadding();long total=duration(),position=seek==null?Math.max(0,player.getCurrentPosition()):seek.candidate();
        if(!pointerSeeking)timeline.setProgress(total>0?(int)(10000d*PlaybackTimeline.clamp(position,total)/total):0);
        timeline.setSecondaryProgress(total>0?(int)(10000d*PlaybackTimeline.clamp(player.getBufferedPosition(),total)/total):0);
        elapsed.setText(PlaybackTimeline.time(position));remaining.setText(total>0?"−"+PlaybackTimeline.time(Math.max(0,total-position)):"Duration unavailable");
        playPause.setText(player.getPlayWhenReady()?"Ⅱ":"▶");playPause.setContentDescription(player.getPlayWhenReady()?"Pause":"Play");
        boolean ended=player.getPlaybackState()==Player.STATE_ENDED;String status = ended ? "FINISHED" : seek == null && !player.getPlayWhenReady() ? "PAUSED" : "";
        state.setText(status);
        state.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        seekHint.setText(seek!=null?PlaybackTimeline.time(position)+"   ·   Select to play   ·   Back to cancel":"");
        long remainingMs=Math.max(0,total-position);endTime.setText(total>0?"Ends at "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(System.currentTimeMillis()+(long)(remainingMs/Math.max(.1d,player.getPlaybackParameters().speed)))):"");
        boolean cc = externalEnabled ? !awaitingFirstCaption : player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_TEXT);
        captions.setText(cc ? "CC On" : "CC");
        buffering.setVisibility(player.getPlaybackState()==Player.STATE_BUFFERING?VISIBLE:GONE);
    }
    void enableExternalSubtitles(Subtitle subtitle) {
        externalSubtitle = subtitle;
        selectExternalSubtitles();
    }

    private void selectExternalSubtitles() {
        externalEnabled = true;
        awaitingFirstCaption = true;
        setEmbeddedSubtitlesEnabled(false);
        downloadedCaptions.setVisibility(VISIBLE);
        refreshExternalCaptions();
        if (awaitingFirstCaption) showNotice("Waiting for the next caption…");
        update();
    }

    private void disableExternalSubtitles() {
        externalEnabled = false;
        awaitingFirstCaption = false;
        downloadedCaptions.setVisibility(GONE);
        refreshExternalCaptions();
    }

    private void setEmbeddedSubtitlesEnabled(boolean enabled) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build());
    }

    private void updateCaptionPadding() {
        if (video.getHeight() == 0) return;
        float padding = visible ? Math.min(.65f, (chrome.getHeight() + dp(16)) / (float) video.getHeight()) : .08f;
        downloadedCaptions.setBottomPaddingFraction(padding);
        if (video.getSubtitleView() != null) video.getSubtitleView().setBottomPaddingFraction(padding);
    }

    private final Runnable captionPulse = this::refreshExternalCaptions;

    private void refreshExternalCaptions() {
        handler.removeCallbacks(captionPulse);
        boolean display = !disposed && player != null && externalEnabled && externalSubtitle != null;
        List<Cue> cues = display ? externalSubtitle.getCues(player.getCurrentPosition() * 1000)
            : java.util.Collections.emptyList();
        if (!displayedCues.equals(cues)) {
            displayedCues = cues;
            downloadedCaptions.setCues(cues);
        }
        if (display && awaitingFirstCaption && !cues.isEmpty()) {
            awaitingFirstCaption = false;
            captions.setText("CC On");
            showNotice("English subtitles on");
        }
        if (display && player.isPlaying()) handler.postDelayed(captionPulse, 100);
    }

    void showNotice(String value){feedback.setText(value);feedback.setVisibility(VISIBLE);feedback.announceForAccessibility(value);handler.removeCallbacks(clearFeedback);handler.postDelayed(clearFeedback,1600);}
    private void toggle(){if(player==null)return;if(seek!=null){commitSeek();return;}if(player.getPlaybackState()==Player.STATE_ENDED){player.seekTo(0);player.play();}else player.setPlayWhenReady(!player.getPlayWhenReady());show();}
    private void skip(long delta){if(!seekable()){showNotice("Seeking is not available yet");return;}player.seekTo(PlaybackTimeline.offset(player.getCurrentPosition(),delta,duration()));showNotice(delta<0?"↶ 10 seconds":"10 seconds ↷");update();scheduleHide();}
    private boolean beginSeek(){if(!seekable()){showNotice("Seeking is not available yet");return false;}if(seek==null){seek=new PlaybackTimeline(player.getCurrentPosition(),duration(),player.getPlayWhenReady());player.pause();}show();return true;}
    private void commitSeek(){if(seek==null||player==null)return;PlaybackTimeline selection=seek;seek=null;player.seekTo(selection.candidate());player.play();show();showNotice("Jumped to "+PlaybackTimeline.time(selection.candidate()));}
    private void cancelSeek(){if(seek==null||player==null)return;boolean resume=seek.wasPlaying;seek=null;player.setPlayWhenReady(resume);show();showNotice("Seek cancelled");}
    private void openPanel(String name){if(seek!=null)cancelSeek();show();handler.removeCallbacks(hideAction);menuTitle.setText(name);menu.removeAllViews();panel.setVisibility(VISIBLE);}
    private void option(String label,boolean selected,Runnable action){Button b=control((selected?"✓  ":"")+label,()->{action.run();closePanel();update();});b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(42));lp.bottomMargin=dp(5);menu.addView(b,lp);}
    private void focusMenu(){if(menu.getChildCount()>0)menu.getChildAt(0).requestFocus();}
    private void closePanel(){panel.setVisibility(GONE);if(visible)timeline.requestFocus();scheduleHide();}
    private String trackLabel(Format format,int type,int number){
        String label=format.label;if(label==null||label.trim().isEmpty()){String language=format.language;label=language==null||language.equals("und")?(type==C.TRACK_TYPE_AUDIO?"Audio track ":"Subtitle track ")+number:Locale.forLanguageTag(language).getDisplayLanguage();}
        if(type==C.TRACK_TYPE_AUDIO&&format.channelCount>0)label+=" · "+(format.channelCount==1?"Mono":format.channelCount==2?"Stereo":format.channelCount+" channels");return label;
    }
    private void showTracks(int type) {
        if (player == null) return;
        boolean textTracks = type == C.TRACK_TYPE_TEXT;
        openPanel(textTracks ? "Subtitles" : "Audio");
        int count = 0;
        if (textTracks) {
            option("Off", !externalEnabled && !player.getCurrentTracks().isTypeSelected(type), () -> {
                disableExternalSubtitles();
                setEmbeddedSubtitlesEnabled(false);
            });
            if (externalSubtitle != null) option("English · downloaded", externalEnabled, this::selectExternalSubtitles);
        }
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                final int index = i;
                option(trackLabel(group.getTrackFormat(i), type, ++count), group.isTrackSelected(i), () -> {
                    if (textTracks) disableExternalSubtitles();
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index)).build());
                });
            }
        }
        if (textTracks && externalSubtitle == null) option("Find English subtitles…", false, actions::findSubtitles);
        if (!textTracks && count == 0) {
            TextView empty = text("Audio tracks are not available yet.", 14, MUTED);
            empty.setPadding(dp(8), 0, dp(8), dp(12));
            menu.addView(empty);
        }
        option("Done", false, () -> {});
        focusMenu();
    }
    boolean back(){if(panel.getVisibility()==VISIBLE){closePanel();return true;}if(seek!=null){cancelSeek();return true;}if(visible){hide();return true;}return false;}
    boolean handleKey(KeyEvent event){
        int key=event.getKeyCode();boolean media=key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE||key==KeyEvent.KEYCODE_MEDIA_REWIND||key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        boolean dpad=key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER;
        if(!media&&!dpad)return false;if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;if(player==null)return true;
        if(media){if(key==KeyEvent.KEYCODE_MEDIA_REWIND||key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){if(beginSeek()){seek.move((key==KeyEvent.KEYCODE_MEDIA_REWIND?-1:1)*PlaybackTimeline.step(event.getRepeatCount()));update();}}else if(event.getRepeatCount()==0){if(seek!=null){commitSeek();if(key==KeyEvent.KEYCODE_MEDIA_PAUSE)player.pause();return true;}if(key==KeyEvent.KEYCODE_MEDIA_PLAY)player.play();else if(key==KeyEvent.KEYCODE_MEDIA_PAUSE)player.pause();else toggle();}return true;}
        if(panel.getVisibility()==VISIBLE){if(event.getRepeatCount()>0&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER))return true;View focus=activity.getCurrentFocus();if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(focus instanceof Button&&isWithin(focus,menu))focus.performClick();}else if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){int i=menu.indexOfChild(focus);for(int n=i+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);n>=0&&n<menu.getChildCount();n+=key==KeyEvent.KEYCODE_DPAD_UP?-1:1)if(menu.getChildAt(n) instanceof Button){menu.getChildAt(n).requestFocus();break;}}return true;}
        if(!visible){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){skip(key==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000);return true;}show();if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0)toggle();}return true;}
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()>0)return true;if(seek!=null)commitSeek();else if(timeline.hasFocus()||hasFocus()&&!buttons.hasFocus())toggle();else {View focus=activity.getCurrentFocus();if(focus instanceof Button)focus.performClick();}return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP){timeline.requestFocus();scheduleHide();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(seek!=null)commitSeek();playPause.requestFocus();scheduleHide();return true;}
        if(timeline.hasFocus()||seek!=null){if(beginSeek()){seek.move((key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1)*PlaybackTimeline.step(event.getRepeatCount()));update();}return true;}
        View focus=activity.getCurrentFocus();int i=transport.indexOf(focus);int n=i+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);if(n>=0&&n<transport.size())transport.get(n).requestFocus();scheduleHide();return true;
    }
    private static boolean isWithin(View child,View parent){for(View v=child;v!=null;){if(v==parent)return true;android.view.ViewParent p=v.getParent();v=p instanceof View?(View)p:null;}return false;}
    void dispose(){disposed=true;handler.removeCallbacksAndMessages(null);if(player!=null)player.removeListener(listener);player=null;}
}
