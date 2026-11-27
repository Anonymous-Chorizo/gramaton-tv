package io.gramaton.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Playback feasibility prototype. No service API or native integration is assumed. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout root;
    private LinearLayout browser;
    private TextView status;
    private Button play;
    private PlayerView playerView;
    private PlaybackControls playbackControls;
    private final java.util.concurrent.ExecutorService subtitleWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int subtitleEpoch;
    private ExoPlayer player;
    private final ArrayList<Source> sources = new ArrayList<>();
    private String titleKey = "";
    private String referrer = "";
    private int sourceIndex;
    private long bufferingSince;
    private boolean active;
    private boolean resolving;
    private TvBrowser tv;
    private AppUpdates appUpdates;
    private String catalogueUrl = "https://gramaton.io/", catalogueLabel = "Home";
    private JSONObject currentMovie;
    private boolean preserveReturningCatalogue;
    private int pageEpoch;
    private boolean accountVisible;
    private String catalogueScript, detailsScript;
    private int selectedSeason, selectedEpisode;
    private boolean documentReady;
    private String pendingSearch, requestedCatalogueUrl = "https://gramaton.io/", lastCatalogueFingerprint = "";
    private int stableCatalogueReads;
    private static final class CatalogueCache {
        final JSONObject data; final long loadedAt = android.os.SystemClock.elapsedRealtime();
        CatalogueCache(JSONObject data) { this.data = data; }
    }
    private final java.util.LinkedHashMap<String, CatalogueCache> catalogueCache = new java.util.LinkedHashMap<String, CatalogueCache>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, CatalogueCache> entry) { return size() > 8; }
    };
    private static final class Source {
        final Uri uri; final int height;
        Source(Uri uri, int height) { this.uri = uri; this.height = height; }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        appUpdates = new AppUpdates(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("Sign in to Gramaton. Your movie library will open automatically.");
        browser.addView(status);
        play = new Button(this);
        play.setText("Done signing in · Open movies");
        play.setOnClickListener(v -> navigateCatalogue("https://gramaton.io/", "Home"));
        browser.addView(play);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { documentReady = false; }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !isSite(uri);
            }
            @Override public void onPageFinished(WebView view, String url) {
                // A browser session from the desktop is not transferred to the TV.
                CookieManager.getInstance().flush();
                if (!url.equals(view.getUrl())) return;
                documentReady = true;
                pageFinished(url);
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                // Read catalogue text as soon as the document is visible, without
                // waiting for every website poster and third-party asset to load.
                if (url.equals(view.getUrl())) { documentReady = true; pageFinished(url); }
            }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame() && tv != null) tv.status("Could not connect. Select Home to retry, or Account to sign in.");
            }
        });
        browser.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        catalogueScript = readAsset("catalogue.js");
        detailsScript = readAsset("details.js");
        tv = new TvBrowser(this, new TvBrowser.Actions() {
            @Override public void navigate(String url, String label) {
                if (label.equals("back")) returnToCatalogue(); else navigateCatalogue(url, label);
            }
            @Override public void open(JSONObject movie) { openMovie(movie); }
            @Override public void play() { subtitleEpoch++; resolveMovie(); }
            @Override public void search(String query) { searchCatalogue(query); }
            @Override public void page(String direction) { turnPage(direction); }
            @Override public void account() { showAccount(); }
            @Override public void checkForUpdates() { appUpdates.check(); }
            @Override public void exitToFireTv() {
                android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                home.addCategory(android.content.Intent.CATEGORY_HOME);
                home.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
            }
            @Override public void retry() {
                if (currentMovie != null) openMovie(currentMovie);
                else { catalogueCache.clear(); navigateCatalogue(catalogueUrl, catalogueLabel); }
            }
            @Override public void episode(int season, int episode) {
                if (currentMovie == null) return;
                try {
                    JSONObject chosen = new JSONObject(currentMovie.toString());
                    chosen.put("url", Uri.parse(chosen.getString("url")).buildUpon().clearQuery()
                        .appendQueryParameter("recent", "true").appendQueryParameter("season", String.valueOf(season))
                        .appendQueryParameter("episode", String.valueOf(episode)).build().toString());
                    openMovie(chosen);
                } catch (Exception ignored) { tv.status("Could not select this episode."); }
            }
        });
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        tv.loading("Home");
        setNativeVisible(true);
        if (saved == null || web.restoreState(saved) == null) web.loadUrl("https://gramaton.io/");
    }

    private String readAsset(String name) {
        try (java.io.InputStream input = getAssets().open(name); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { return "JSON.stringify({cards:[],login:false})"; }
    }
    private void setNativeVisible(boolean visible) {
        tv.setVisibility(visible ? View.VISIBLE : View.GONE);
        browser.setDescendantFocusability(visible ? android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS : android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        browser.setImportantForAccessibility(visible ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        web.setFocusable(!visible); web.setFocusableInTouchMode(!visible);
    }
    private void showAccount() {
        accountVisible = true; pageEpoch++; currentMovie = null; catalogueCache.clear();
        setNativeVisible(false); web.requestFocus();
        status.setText("Account / sign-in. Select Open movies above when finished.");
        web.loadUrl("https://gramaton.io/users");
    }
    private void navigateCatalogue(String url, String label) {
        if (!isSite(Uri.parse(url))) return;
        accountVisible = false; currentMovie = null; pendingSearch = null; pageEpoch++;
        preserveReturningCatalogue = false; tv.rememberPage(); tv.hideDetails(); tv.closeSearch(); tv.setPageKey(catalogueStateKey(url));
        catalogueLabel = label; catalogueUrl = url; requestedCatalogueUrl = url;
        setNativeVisible(true);
        CatalogueCache cached = catalogueCache.get(url);
        if (cached != null) {
            try { tv.render(cached.data, label); } catch (Exception ignored) { cached = null; }
        }
        if (cached == null) tv.loading(label);
        else {
            if (android.os.SystemClock.elapsedRealtime() - cached.loadedAt < 60000 && url.equals(web.getUrl()) && documentReady) { tv.finishLoading(); return; }
            tv.refreshing(label);
        }
        web.loadUrl(url);
    }
    private void openMovie(JSONObject movie) {
        String url = movie.optString("url");
        if (!isMovie(Uri.parse(url))) return;
        subtitleEpoch++; currentMovie = movie; selectedSeason = 0; selectedEpisode = 0; accountVisible = false; pageEpoch++;
        tv.showDetails(movie); web.loadUrl(url);
    }
    private void returnToCatalogue() {
        subtitleEpoch++; currentMovie = null; accountVisible = false; pendingSearch = null; pageEpoch++;
        preserveReturningCatalogue = true; tv.hideDetails(); setNativeVisible(true);
        CatalogueCache previous = catalogueCache.get(catalogueUrl);
        if (previous == null) tv.loading(catalogueLabel);
        else if (catalogueLabel.equals("Home") || catalogueLabel.equals("Watchlist")) {
            try { tv.render(previous.data, catalogueLabel); } catch (Exception error) { preserveReturningCatalogue = false; }
        }
        // Existing native rows stay visible and retain focus while website context refreshes.
        web.loadUrl(catalogueUrl);
    }
    private void pageFinished(String url) {
        if (url.contains("/session/")) { catalogueCache.clear(); setNativeVisible(false); web.requestFocus(); return; }
        if (accountVisible) return;
        if (pendingSearch != null) { String query = pendingSearch; pendingSearch = null; searchCatalogue(query); return; }
        if (isMovie(Uri.parse(url))) {
            if (currentMovie != null) pollDetails(++pageEpoch, 0);
            return;
        }
        if (currentMovie != null) return;
        catalogueUrl = url; lastCatalogueFingerprint = ""; stableCatalogueReads = 0; pollCatalogue(++pageEpoch, 0);
    }
    private void pollCatalogue(int epoch, int attempt) {
        handler.postDelayed(() -> {
            if (isDestroyed() || epoch != pageEpoch || currentMovie != null || accountVisible) return;
            web.evaluateJavascript(catalogueScript, encoded -> {
                if (epoch != pageEpoch) return;
                boolean settled = false;
                try {
                    JSONObject data = new JSONObject((String)new org.json.JSONTokener(encoded).nextValue());
                    if (data.optBoolean("login")) { catalogueCache.clear(); setNativeVisible(false); web.requestFocus(); return; }
                    setNativeVisible(true);
                    if ((data.getJSONArray("cards").length() > 0 && data.optBoolean("ready", true)) || data.optBoolean("empty") || attempt >= 13) {
                        String fingerprint = data.getJSONArray("cards").toString();
                        stableCatalogueReads = fingerprint.equals(lastCatalogueFingerprint) ? stableCatalogueReads + 1 : 0;
                        lastCatalogueFingerprint = fingerprint; settled = stableCatalogueReads >= 2;
                        String actual = web.getUrl();
                        if (actual != null && isSite(Uri.parse(actual)) && !isMovie(Uri.parse(actual))) catalogueUrl = actual;
                        tv.setPageKey(catalogueStateKey(catalogueUrl));
                        CatalogueCache entry = new CatalogueCache(data); catalogueCache.put(catalogueUrl, entry);
                        if (!requestedCatalogueUrl.isEmpty()) catalogueCache.put(requestedCatalogueUrl, entry);
                        if (!preserveReturningCatalogue) tv.render(data, catalogueLabel);
                    }
                } catch (Exception error) { tv.status("Could not read this page. Choose a section above to retry."); }
                if (!settled && attempt < 13) pollCatalogue(epoch, attempt + 1);
            });
        }, attempt == 0 ? 500 : 750);
    }
    private void pollDetails(int epoch, int attempt) {
        handler.postDelayed(() -> {
            if (isDestroyed() || epoch != pageEpoch || currentMovie == null) return;
            web.evaluateJavascript(detailsScript.replace("__TITLE__", JSONObject.quote(currentMovie.optString("title"))), encoded -> {
                if (epoch != pageEpoch) return;
                try {
                    JSONObject data = new JSONObject((String)new org.json.JSONTokener(encoded).nextValue());
                    if (data.optBoolean("ready")) {
                        selectedSeason = data.optInt("season"); selectedEpisode = data.optInt("episode");
                        currentMovie.put("subtitleYear", data.optString("year", ""));
                        currentMovie.put("subtitleImdbId", data.optString("imdb", ""));
                        String key = playbackKey(Uri.parse(web.getUrl()));
                        tv.detailsReady(data.optString("backdrop"), data.optString("poster"), data.optString("description"), data.optString("year"), data.optString("genre"), data.optString("cast"), getPreferences(0).getLong(key, 0), data.optJSONArray("episodes"));
                        return;
                    }
                } catch (Exception ignored) { }
                if (attempt < 12) pollDetails(epoch, attempt + 1);
                else tv.status("No movie source found. Press Back to choose another movie.");
            });
        }, attempt == 0 ? 400 : 750);
    }
    private void searchCatalogue(String query) {
        preserveReturningCatalogue = false;
        pageEpoch++; accountVisible = false; currentMovie = null; tv.rememberPage(); tv.hideDetails(); requestedCatalogueUrl = "";
        catalogueLabel = "Search: " + query; tv.setPageKey("search:" + query); tv.resetSignature(); tv.loading(catalogueLabel);
        if (web.getUrl() != null && isMovie(Uri.parse(web.getUrl()))) { pendingSearch = query; web.loadUrl("https://gramaton.io/"); return; }
        if (!documentReady) { pendingSearch = query; return; }
        String js = "(()=>{const e=document.getElementById('search-input-regular')||document.getElementById('search-input-mobile');if(!e)return false;e.value=" + JSONObject.quote(query) + ";e.dispatchEvent(new Event('input',{bubbles:true}));const a=e.parentElement.querySelector('a[onclick]');if(a){a.click();return true;}if(typeof search_button_event==='function'){search_button_event(e.id);return true;}return false;})()";
        web.evaluateJavascript(js, result -> {
            if (!"true".equals(result)) tv.status("Search is not ready. Select Home, then try Search again.");
        });
    }
    private void turnPage(String direction) {
        preserveReturningCatalogue = false;
        tv.rememberPage(); requestedCatalogueUrl = ""; int epoch = ++pageEpoch; tv.loading(catalogueLabel);
        String js = "(()=>{const a=Array.from(document.querySelectorAll('a')).find(a=>a.textContent.trim().toLowerCase()===" + JSONObject.quote(direction) + ");if(!a)return false;a.click();return true;})()";
        web.evaluateJavascript(js, result -> { if ("true".equals(result)) pollCatalogue(epoch, 0); else tv.status("No more pages in this direction."); });
    }

    private String catalogueStateKey(String url) {
        Uri uri = Uri.parse(url); String path = uri.getPath();
        if (path != null && path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        Uri.Builder key = uri.buildUpon().path(path).clearQuery().fragment(null);
        for (String name : new String[]{"q", "page", "type", "abc", "genres", "sortby", "quality"}) {
            String value = uri.getQueryParameter(name); if (value != null) key.appendQueryParameter(name, value);
        }
        return key.build().toString();
    }
    private boolean isSite(Uri uri) {
        return "https".equals(uri.getScheme()) && "gramaton.io".equals(uri.getHost());
    }
    private boolean isMovie(Uri uri) {
        return isSite(uri) && uri.getPath() != null && uri.getPath().matches("/(movies|series)/[0-9]+[^/]*");
    }
    private String playbackKey(Uri page) {
        String key = page.getPath();
        if (key != null && key.startsWith("/series/")) key += "?season=" + selectedSeason + "&episode=" + selectedEpisode;
        return key;
    }
    private void resolveMovie() {
        if (resolving || player != null || currentMovie == null) return;
        Uri page = Uri.parse(web.getUrl() == null ? "" : web.getUrl());
        if (!isMovie(page)) { tv.status("Movie is loading. Try Play again in a moment."); return; }
        resolving = true;
        play.setEnabled(false);
        referrer = page.toString();
        titleKey = playbackKey(page);
        // Read only links deliberately offered by the authenticated movie page.
        // No injected bridge, cookie export, guessed media URLs, or stored access tokens.
        web.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a=>({href:a.href,label:a.textContent.trim()})).filter(a=>/\\/player\\/(videojs5|native)\\.html/.test(a.href)))", encoded -> {
            resolving = false;
            play.setEnabled(true);
            if (!active || !referrer.equals(web.getUrl())) return;
            try {
                Object decoded = new org.json.JSONTokener(encoded).nextValue();
                JSONArray links = new JSONArray((String) decoded);
                sources.clear();
                for (int i = 0; i < links.length(); i++) {
                    JSONObject link = links.getJSONObject(i);
                    Uri wrapper = Uri.parse(link.getString("href"));
                    if (!isSite(wrapper) || wrapper.getEncodedQuery() == null) continue;
                    JSONObject payload = new JSONObject(Uri.decode(wrapper.getEncodedQuery()));
                    Uri media = Uri.parse(payload.getString("v"));
                    String host = media.getHost();
                    if (!"https".equals(media.getScheme()) || host == null || !host.endsWith(".gramaton.io")) continue;
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("(\\d{3,4})p", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(media.getPath() == null ? "" : media.getPath());
                    if (!match.find()) continue;
                    int height = Integer.parseInt(match.group(1));
                    boolean duplicate = false;
                    for (Source source : sources) if (source.uri.equals(media)) duplicate = true;
                    if (!duplicate) sources.add(new Source(media, height));
                }
                sources.sort(Comparator.comparingInt((Source source) -> source.height).reversed());
                if (sources.isEmpty()) { tv.status("No playable movie sources. Press Back to choose another movie."); return; }
                sourceIndex = 0;
                tv.finishLoading();
                browser.setVisibility(View.GONE);
                tv.setVisibility(View.GONE);
                playerView = new PlayerView(this);
                playerView.setBackgroundColor(android.graphics.Color.BLACK);
                playerView.setShutterBackgroundColor(android.graphics.Color.BLACK);
                playerView.setUseController(false);
                root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
                playbackControls = new PlaybackControls(this, playerView, currentMovie.optString("title"), currentMovie.optString("meta"), this::findPlaybackSubtitles);
                root.addView(playbackControls, new FrameLayout.LayoutParams(-1, -1));
                enterFullscreen();
                web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(v=>v.pause())", null);
                web.onPause();
                web.pauseTimers();
                startSource(getPreferences(0).getLong(titleKey, 0), true);
                handler.post(tick);
            } catch (Exception error) {
                stopPlayback("This movie page could not be opened. Reload it and try again.");
            }
        });
    }


    private int playbackSubtitleRequest = -1;

    private void findPlaybackSubtitles() {
        if (player == null || currentMovie == null || playbackSubtitleRequest == subtitleEpoch) return;
        final int epoch = ++subtitleEpoch;
        playbackSubtitleRequest = epoch;
        final PlaybackControls controls = playbackControls;
        final JSONObject movie = currentMovie;
        final int season = selectedSeason, episode = selectedEpisode;
        final String key = titleKey;
        final long durationMs = player.getDuration();
        controls.showNotice("Searching for English subtitles…");
        subtitleWorker.execute(() -> {
            androidx.media3.extractor.text.Subtitle subtitle = null;
            String notice = "No matching English subtitles found";
            try {
                subtitle = SubtitleProvider.find(this, movie, season, episode, key, durationMs);
            } catch (SubtitleProvider.QuotaExceeded quota) {
                notice = "Subtitle service limit reached. Try again later.";
            } catch (Exception error) {
                notice = "Couldn’t reach or read the subtitle service. Try again.";
            }
            final androidx.media3.extractor.text.Subtitle result = subtitle;
            final String message = notice;
            handler.post(() -> {
                if (playbackSubtitleRequest == epoch) playbackSubtitleRequest = -1;
                if (!active || epoch != subtitleEpoch || playbackControls != controls || player == null) return;
                if (result == null) controls.showNotice(message);
                else controls.enableExternalSubtitles(result);
            });
        });
    }

    private void enterFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else getWindow().getDecorView().setSystemUiVisibility(5894);
    }
    private void startSource(long position, boolean shouldPlay) {
        boolean embeddedSubtitles = player != null && !player.getTrackSelectionParameters()
            .disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT);
        if (player != null) { if (playbackControls != null) playbackControls.bind(null); playerView.setPlayer(null); player.release(); }
        Source source = sources.get(sourceIndex);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(web.getSettings().getUserAgentString())
            .setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", referrer));
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this, http))).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);
        if (playbackControls != null) playbackControls.requestFocus();
        bufferingSince = 0;
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) getPreferences(0).edit().remove(titleKey).apply();
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) { if (!isPlaying) savePosition(); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition();
            }
            @Override public void onPlayerError(PlaybackException error) {
                // Expired links need a page refresh; never log signed URLs or retry forever.
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    stopPlayback("Playback link rejected. Reload the movie page to obtain fresh links.");
                } else if (!lowerQuality()) {
                    stopPlayback("Playback failed (code " + error.errorCode + "). Try another server on the movie page.");
                }
            }
        });
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setPreferredTextLanguage("en").setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !embeddedSubtitles).build());
        player.setMediaItem(MediaItem.fromUri(source.uri), Math.max(0, position));
        player.prepare();
        player.setPlayWhenReady(shouldPlay);
        if (playbackControls != null) playbackControls.bind(player);
    }
    private boolean lowerQuality() {
        if (player == null || sourceIndex + 1 >= sources.size()) return false;
        long position = player.getCurrentPosition();
        boolean shouldPlay = player.getPlayWhenReady();
        sourceIndex++;
        startSource(position, shouldPlay);
        return true;
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null || !active) return;
            savePosition();
            if (player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady()) {
                if (bufferingSince == 0) bufferingSince = android.os.SystemClock.elapsedRealtime();
                if (android.os.SystemClock.elapsedRealtime() - bufferingSince > 12000) {
                    if (!lowerQuality()) bufferingSince = android.os.SystemClock.elapsedRealtime();
                }
            } else bufferingSince = 0;
            handler.postDelayed(this, 2000);
        }
    };
    private void savePosition() {
        if (player != null && player.getPlaybackState() != Player.STATE_ENDED && player.getCurrentPosition() > 0)
            getPreferences(0).edit().putLong(titleKey, player.getCurrentPosition()).apply();
    }
    private void stopPlayback(String message) {
        handler.removeCallbacks(tick);
        if (playbackControls != null) { playbackControls.dispose(); root.removeView(playbackControls); playbackControls = null; }
        if (player != null) {
            savePosition();
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        if (playerView != null) { root.removeView(playerView); playerView = null; }
        sources.clear();
        browser.setVisibility(View.VISIBLE);
        setNativeVisible(true);
        web.resumeTimers();
        web.onResume();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.systemBars());
        } else getWindow().getDecorView().setSystemUiVisibility(0);
        tv.updateResumePosition(getPreferences(0).getLong(titleKey, 0));
        if (message != null) tv.status(message);
        tv.restoreFocus();
    }
    @Override protected void onResume() { super.onResume(); active = true; if (web != null) web.onResume(); }
    @Override protected void onPause() {
        active = false; subtitleEpoch++;
        if (player != null) stopPlayback(null);
        web.onPause();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); super.onSaveInstanceState(out); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (playbackControls != null && playbackControls.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (player != null) { if (playbackControls != null && playbackControls.back()) return; stopPlayback(null); }
        else if (tv.getVisibility() == View.VISIBLE && tv.closeSearch()) return;
        else if (tv.isDetailShowing() || accountVisible || tv.getVisibility() != View.VISIBLE) returnToCatalogue();
        else if (!catalogueLabel.equals("Home")) navigateCatalogue("https://gramaton.io/", "Home");
        else super.onBackPressed();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (tv != null) tv.trimArtwork();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); subtitleEpoch++; subtitleWorker.shutdownNow();
        if (playbackControls != null) playbackControls.dispose();
        if (player != null) { savePosition(); player.release(); }
        appUpdates.close();
        tv.dispose();
        web.destroy();
        super.onDestroy();
    }
}
