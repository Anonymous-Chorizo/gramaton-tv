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
    private boolean preserveCatalogueViews;
    private int pageEpoch;
    private boolean accountVisible;
    private String catalogueScript, detailsScript;
    private int selectedSeason, selectedEpisode;
    private boolean documentReady;
    private JSONObject gridSourcePage;
    private boolean gridDocumentReady, loadingGridPage, awaitingGridRetry;
    private String gridStartUrl;
    private String applyingGridFilter, applyingGridFilterValue;
    private final java.util.LinkedHashMap<String, String> queuedGridFilters = new java.util.LinkedHashMap<>();
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
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { documentReady = false; gridDocumentReady = false; }
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
                if (pendingSearch == null && url.equals(view.getUrl())) { documentReady = true; pageFinished(url); }
            }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame() && tv != null && !loadingGridPage && !awaitingGridRetry) {
                    tv.status("Could not connect. Select Home to retry, or Account to sign in.");
                }
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
            @Override public boolean loadMore(boolean retry) { return loadNextGridPage(retry); }
            @Override public void filter(String parameter, String value) { filterCatalogue(parameter, value); }
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
                else if (catalogueLabel.startsWith("Search: ")) searchCatalogue(catalogueLabel.substring("Search: ".length()));
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
        suspendGridPaging();
        accountVisible = true; pageEpoch++; currentMovie = null; catalogueCache.clear();
        setNativeVisible(false); web.requestFocus();
        status.setText("Account / sign-in. Select Open movies above when finished.");
        web.loadUrl("https://gramaton.io/users");
    }
    private void navigateCatalogue(String url, String label) {
        if (!isSite(Uri.parse(url))) return;
        suspendGridPaging();
        gridSourcePage = null;
        gridStartUrl = null;
        if (TvBrowser.isGridListing(label)) tv.resetSignature();
        accountVisible = false; currentMovie = null; pendingSearch = null; pageEpoch++;
        preserveCatalogueViews = false; tv.rememberPage(); tv.hideDetails(); tv.closeSearch(); tv.setPageKey(catalogueStateKey(url));
        catalogueLabel = label; catalogueUrl = url; requestedCatalogueUrl = url;
        setNativeVisible(true);
        CatalogueCache cached = catalogueCache.get(url);
        if (cached != null) {
            try { tv.render(cached.data, label); } catch (Exception ignored) { cached = null; }
        }
        if (cached == null) tv.loading(label);
        else {
            if (android.os.SystemClock.elapsedRealtime() - cached.loadedAt < 60000 && url.equals(web.getUrl()) && documentReady) {
                tv.finishLoading();
                setGridDocumentReady(cached.data);
                return;
            }
            tv.refreshing(label);
        }
        web.loadUrl(url);
    }
    private void openMovie(JSONObject movie) {
        String url = movie.optString("url");
        if (!isMovie(Uri.parse(url))) return;
        suspendGridPaging();
        subtitleEpoch++; currentMovie = movie; selectedSeason = 0; selectedEpisode = 0; accountVisible = false; pageEpoch++;
        tv.showDetails(movie); web.loadUrl(url);
    }
    private void returnToCatalogue() {
        boolean returningFromAccount = accountVisible;
        if (returningFromAccount && TvBrowser.isGridListing(catalogueLabel)) {
            tv.focusCatalogueWhenReady();
            if (catalogueLabel.startsWith("Search: ")) searchCatalogue(catalogueLabel.substring("Search: ".length()));
            else navigateCatalogue(gridStartUrl == null ? catalogueUrl : gridStartUrl, catalogueLabel);
            return;
        }
        suspendGridPaging();
        subtitleEpoch++; currentMovie = null; accountVisible = false; pendingSearch = null; pageEpoch++;
        preserveCatalogueViews = !returningFromAccount && tv.hasRenderedCatalogue(catalogueLabel);
        if (returningFromAccount) { tv.resetSignature(); tv.focusCatalogueWhenReady(); }
        tv.hideDetails(); setNativeVisible(true);
        CatalogueCache previous = catalogueCache.get(catalogueUrl);
        if (previous != null && (catalogueLabel.equals("Home") || catalogueLabel.equals("Watchlist"))) {
            try { tv.render(previous.data, catalogueLabel); preserveCatalogueViews = true; }
            catch (Exception error) { preserveCatalogueViews = false; }
        }
        if (!preserveCatalogueViews) tv.loading(catalogueLabel);
        // Keep the displayed catalogue and focus while the WebView reloads its current page.
        web.loadUrl(catalogueUrl);
    }
    private void pageFinished(String url) {
        if (url.contains("/session/")) { suspendGridPaging(); catalogueCache.clear(); setNativeVisible(false); web.requestFocus(); return; }
        if (accountVisible) return;
        if (loadingGridPage || awaitingGridRetry) return;
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
                    if (data.optBoolean("login")) { suspendGridPaging(); catalogueCache.clear(); setNativeVisible(false); web.requestFocus(); return; }
                    setNativeVisible(true);
                    boolean searchMatches = !catalogueLabel.startsWith("Search: ") || catalogueLabel.substring("Search: ".length()).equalsIgnoreCase(data.optString("query"));
                    if (!searchMatches) {
                        if (attempt < 13) pollCatalogue(epoch, attempt + 1);
                        else tv.status("Search did not return a results page. Try again.");
                        return;
                    }
                    boolean filtering = applyingGridFilter != null;
                    if (filtering && !gridFilterMatches(data)) {
                        if (attempt < 13) pollCatalogue(epoch, attempt + 1);
                        else failGridFilter("The listing didn’t apply the selected filter. Try again.");
                        return;
                    }
                    if (attempt >= 13 && !data.optBoolean("ready", true) && TvBrowser.isGridListing(catalogueLabel)) {
                        tv.status("The listing is taking longer than expected.");
                        return;
                    }
                    if ((data.getJSONArray("cards").length() > 0 && data.optBoolean("ready", true)) || data.optBoolean("empty") || attempt >= 13) {
                        String fingerprint = data.toString();
                        stableCatalogueReads = fingerprint.equals(lastCatalogueFingerprint) ? stableCatalogueReads + 1 : 0;
                        lastCatalogueFingerprint = fingerprint; settled = stableCatalogueReads >= 2;
                        String actual = web.getUrl();
                        if (actual != null && isSite(Uri.parse(actual)) && !isMovie(Uri.parse(actual))) catalogueUrl = actual;
                        if (!preserveCatalogueViews) tv.setPageKey(catalogueStateKey(catalogueUrl));
                        CatalogueCache entry = new CatalogueCache(data); catalogueCache.put(catalogueUrl, entry);
                        if (!requestedCatalogueUrl.isEmpty()) catalogueCache.put(requestedCatalogueUrl, entry);
                        if (!preserveCatalogueViews) tv.render(data, catalogueLabel);
                        setGridDocumentReady(data);
                        if (filtering) { applyingGridFilter = null; applyQueuedGridFilter(); return; }
                    }
                } catch (Exception error) { tv.status("Could not read this page. Choose a section from the menu to retry."); }
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
        preserveCatalogueViews = false;
        suspendGridPaging();
        gridSourcePage = null;
        gridStartUrl = null;
        pageEpoch++; accountVisible = false; currentMovie = null; tv.rememberPage(); tv.hideDetails(); requestedCatalogueUrl = "";
        catalogueLabel = "Search: " + query; tv.setPageKey("search:" + query); tv.resetSignature(); tv.loading(catalogueLabel);
        if (web.getUrl() != null && !"/".equals(Uri.parse(web.getUrl()).getPath())) { pendingSearch = query; web.loadUrl("https://gramaton.io/"); return; }
        if (!documentReady) { pendingSearch = query; return; }
        lastCatalogueFingerprint = "";
        stableCatalogueReads = 0;
        submitWebSearch(query, pageEpoch, 0);
    }

    private void submitWebSearch(String query, int epoch, int attempt) {
        if (isDestroyed() || epoch != pageEpoch) return;
        String submit = String.format(java.util.Locale.ROOT, """
            (() => {
                const desktop = document.getElementById('sidenav-default');
                const mobile = desktop && getComputedStyle(desktop).display === 'none';
                const field = document.getElementById(mobile ? 'search-input-mobile' : 'search-input-regular');
                if (!field || typeof search_button_event !== 'function') return false;
                field.value = %s;
                field.dispatchEvent(new Event('input', {bubbles:true}));
                search_button_event(field.id);
                return true;
            })()
            """, JSONObject.quote(query));
        web.evaluateJavascript(submit, result -> {
            if (epoch != pageEpoch) return;
            if ("true".equals(result)) pollCatalogue(epoch, 0);
            else if (attempt < 5) handler.postDelayed(() -> submitWebSearch(query, epoch, attempt + 1), 300);
            else tv.status("Search is not ready. Select Home, then try Search again.");
        });
    }

    private void suspendGridPaging() {
        gridDocumentReady = false;
        loadingGridPage = false;
        awaitingGridRetry = false;
        applyingGridFilter = null;
        queuedGridFilters.clear();
        tv.suspendGridPaging();
    }

    private void setGridDocumentReady(JSONObject data) {
        if (!TvBrowser.isGridListing(catalogueLabel) || !data.optBoolean("ready")) return;
        if (!preserveCatalogueViews) gridStartUrl = catalogueUrl;
        gridSourcePage = data;
        gridDocumentReady = true;
        tv.gridDocumentReady();
    }

    private boolean loadNextGridPage(boolean retry) {
        if (loadingGridPage || gridSourcePage == null || !gridSourcePage.optBoolean("next") ||
                currentMovie != null || accountVisible) return false;
        if (!retry && (!gridDocumentReady || !documentReady)) return false;
        JSONObject previous = gridSourcePage;
        int expected = previous.optInt("nextPage");
        int epoch = ++pageEpoch;
        loadingGridPage = true;
        awaitingGridRetry = false;
        preserveCatalogueViews = true;
        if (retry) {
            // A failed Next link may have left the WebView on a different page.
            // Reload the last displayed catalogue page before retrying, without replacing the native grid.
            documentReady = false;
            gridDocumentReady = false;
            web.stopLoading();
            web.loadUrl(catalogueUrl);
            pollNextGridPage(epoch, previous, expected, 0, true);
        } else requestNextGridPage(epoch, previous, expected);
        return true;
    }

    private void requestNextGridPage(int epoch, JSONObject previous, int expected) {
        String next = String.format(java.util.Locale.ROOT, """
            (() => {
                const expected = %d;
                const current = Number(new URLSearchParams(location.search).get('page'));
                if (expected && current === expected) return true;
                const link = Array.from(document.querySelectorAll('.pagination a')).find(a =>
                    (a.dataset.type === 'next' || a.textContent.trim().toLowerCase() === 'next') &&
                    !a.closest('.disabled') && a.getAttribute('aria-disabled') !== 'true');
                if (!link) return true;
                const target = Number((link.getAttribute('onclick') || '').split('(')[1]?.split(')')[0]);
                if (expected && target > expected) return true;
                if (expected && target && target !== expected) return false;
                if (link.getAttribute('href') === '#') {
                    link.addEventListener('click', event => event.preventDefault(), {once:true});
                }
                link.click();
                return true;
            })()
            """, expected);
        web.evaluateJavascript(next, result -> {
            if (epoch != pageEpoch) return;
            if ("true".equals(result)) pollNextGridPage(epoch, previous, expected, 0, false);
            else finishGridPage(false);
        });
    }

    private void pollNextGridPage(int epoch, JSONObject previous, int expected, int attempt, boolean restoringSource) {
        handler.postDelayed(() -> {
            if (isDestroyed() || epoch != pageEpoch || !loadingGridPage) return;
            web.evaluateJavascript(catalogueScript, encoded -> {
                if (epoch != pageEpoch || !loadingGridPage) return;
                try {
                    JSONObject data = new JSONObject((String) new org.json.JSONTokener(encoded).nextValue());
                    if (data.optBoolean("login")) {
                        suspendGridPaging(); catalogueCache.clear(); setNativeVisible(false); web.requestFocus(); return;
                    }
                    int page = data.optInt("page");
                    JSONArray cards = data.getJSONArray("cards");
                    boolean ready = data.optBoolean("ready") && !data.optBoolean("home");
                    if (restoringSource && documentReady && catalogueUrl.equals(web.getUrl()) && ready && data.optBoolean("next") &&
                            page == previous.optInt("page") && (expected == 0 || expected == data.optInt("nextPage"))) {
                        requestNextGridPage(epoch, previous, expected);
                        return;
                    }
                    boolean changedPage = expected > 0 && page == expected;
                    if (!changedPage && (expected == 0 || page == 0)) {
                        changedPage = !sameGridTitles(previous.getJSONArray("cards"), cards);
                    }
                    if (!restoringSource && ready && changedPage && (cards.length() > 0 || data.optBoolean("empty"))) {
                        gridSourcePage = data;
                        String actual = web.getUrl();
                        if (actual != null && isSite(Uri.parse(actual))) {
                            catalogueUrl = actual;
                            catalogueCache.put(actual, new CatalogueCache(data));
                        }
                        tv.appendGridPage(cards, data.optBoolean("next"));
                        gridDocumentReady = true;
                        finishGridPage(true);
                        return;
                    }
                } catch (Exception ignored) { /* The catalogue may still be replacing its results. */ }
                if (attempt < 24) pollNextGridPage(epoch, previous, expected, attempt + 1, restoringSource);
                else finishGridPage(false);
            });
        }, attempt == 0 ? 200 : 400);
    }

    private boolean sameGridTitles(JSONArray first, JSONArray second) {
        if (first.length() != second.length()) return false;
        for (int i = 0; i < first.length(); i++) {
            if (!first.optJSONObject(i).optString("url").equals(second.optJSONObject(i).optString("url"))) return false;
        }
        return true;
    }

    private void finishGridPage(boolean success) {
        loadingGridPage = false;
        awaitingGridRetry = !success;
        if (success) applyQueuedGridFilter();
        else tv.failGridPage();
    }

    private void applyQueuedGridFilter() {
        if (queuedGridFilters.isEmpty()) return;
        String parameter = queuedGridFilters.keySet().iterator().next();
        filterCatalogue(parameter, queuedGridFilters.remove(parameter));
    }

    private boolean gridFilterMatches(JSONObject page) {
        JSONObject filters = page.optJSONObject("filters");
        JSONObject option = filters == null ? null : filters.optJSONObject("sortby".equals(applyingGridFilter) ? "sort" : "genre");
        return page.optBoolean("ready") && option != null && applyingGridFilterValue.equalsIgnoreCase(option.optString("current"));
    }

    private void failGridFilter(String message) {
        applyingGridFilter = null;
        queuedGridFilters.clear();
        tv.status(message);
    }

    private void filterCatalogue(String parameter, String value) {
        if (loadingGridPage || applyingGridFilter != null || (awaitingGridRetry && !catalogueUrl.equals(web.getUrl()))) {
            queuedGridFilters.put(parameter, value);
            return;
        }
        if (!documentReady || web.getUrl() == null || !isSite(Uri.parse(web.getUrl()))) {
            tv.status("The filters are loading. Try again in a moment.");
            return;
        }
        preserveCatalogueViews = false;
        gridDocumentReady = false;
        awaitingGridRetry = false;
        applyingGridFilter = parameter;
        applyingGridFilterValue = value;
        tv.suspendGridPaging();
        tv.rememberPage();
        requestedCatalogueUrl = "";
        int epoch = ++pageEpoch;
        lastCatalogueFingerprint = "";
        stableCatalogueReads = 0;
        tv.loading(catalogueLabel);
        String chooseFilter = String.format(java.util.Locale.ROOT, """
            (() => {
                const parameter = %s, value = %s;
                const genreOptions = Array.from(document.querySelectorAll('.genre-filter input.checkbox[value]'));
                const genreValue = input => input.value.replace(/_/g, ' ');
                const activeGenres = parameter === 'genres' ? value : new URLSearchParams(location.search).get('genres') || '';
                const wanted = activeGenres.toLowerCase().split(',');
                const choice = parameter === 'sortby'
                    ? Array.from(document.querySelectorAll('.sortby-filter li .option-value')).find(option => option.textContent.trim() === value)
                    : value ? genreOptions.find(input => genreValue(input) === value) : document.querySelector('.genre-filter #selectall');
                if (!choice || !genreOptions.length) return false;
                for (const input of genreOptions) input.checked = wanted.includes(genreValue(input).toLowerCase());
                choice.closest('li').click();
                return true;
            })()
            """, JSONObject.quote(parameter), JSONObject.quote(value));
        web.evaluateJavascript(chooseFilter, result -> {
            if (epoch != pageEpoch) return;
            if ("true".equals(result)) pollCatalogue(epoch, 0);
            else failGridFilter("This filter isn’t available. Try reloading the listing.");
        });
    }

    private String catalogueStateKey(String url) {
        Uri uri = Uri.parse(url); String path = uri.getPath();
        if (path != null && path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        Uri.Builder key = uri.buildUpon().path(path).clearQuery().fragment(null);
        for (String name : new String[]{"q", "search", "type", "abc", "genres", "sortby", "quality"}) {
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
        else if (tv.dismissDrawer()) return;
        else if (!catalogueLabel.equals("Home")) { tv.focusCatalogueWhenReady(); navigateCatalogue("https://gramaton.io/", "Home"); }
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
