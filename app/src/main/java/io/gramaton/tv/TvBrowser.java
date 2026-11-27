package io.gramaton.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Native TV presentation and explicit remote navigation. Website data stays outside this view. */
final class TvBrowser extends FrameLayout {
    interface Actions {
        void navigate(String url, String label); void open(JSONObject movie); void play();
        void search(String query); void account(); void checkForUpdates(); void retry(); void exitToFireTv();
        boolean loadMore(boolean retry);
        void episode(int season, int episode);
        void filter(String parameter, String value);
    }
    private static final int BG = 0xff090e17, WHITE = 0xfff5f7fb, MUTED = 0xffaebbd0;
    private final Activity activity;
    private final Actions actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TvImageLoader artwork = new TvImageLoader();
    private final LinearLayout browserColumn, searchBar, gridToolbar, rows, details, loadingPanel;
    private final TvNavigationDrawer drawer;
    private final FrameLayout content, hero;
    private final ImageView heroImage;
    private FrameLayout detailArt;
    private ImageView detailPoster, detailBackdrop;
    private final TextView title, subtitle, description, kicker, message, loadingTitle, loadingStage, gridTitle;
    private final ProgressBar spinner, refreshSpinner, moreSpinner;
    private final EditText query;
    private final ScrollView scroller;
    private final TvPosterGrid grid;
    private final Button retry, sortFilter, genreFilter, retryPage;
    private final List<List<View>> cards = new ArrayList<>();
    private final Map<String, View> cardViews = new HashMap<>();
    private final Map<String, HorizontalScrollView> horizontalRows = new LinkedHashMap<>();
    private final List<Poster> posters = new ArrayList<>();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, String> homeSelections = new HashMap<>();
    private View lastCard;
    private boolean detailShowing, loading, artworkQueued, changingPage;
    private boolean focusCatalogueOnRender = true;
    private boolean gridMode;
    private boolean moreAvailable, moreLoading, moreFailed;
    private String signature = "", pageLabel = "Home", pageKey = "https://gramaton.io/", focusedKey = "";
    private String renderedLabel = "";
    private String heroUrl = "";
    private JSONObject selectedMovie;
    private static final class Poster {
        final String url; final ImageView view;
        Poster(String url, ImageView view) { this.url = url; this.view = view; }
    }
    private static final class Snapshot {
        String key; int y, gridPosition, gridOffset;
        final Map<String, Integer> x = new HashMap<>();
    }

    TvBrowser(Activity activity, Actions actions) {
        super(activity); this.activity = activity; this.actions = actions;
        setBackgroundColor(BG);
        browserColumn = new LinearLayout(activity);
        browserColumn.setOrientation(LinearLayout.VERTICAL);
        browserColumn.setClipChildren(false);
        browserColumn.setClipToPadding(false);
        addView(browserColumn, new FrameLayout.LayoutParams(-1, -1));

        refreshSpinner = new ProgressBar(activity);
        refreshSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff70b8ff));
        refreshSpinner.setVisibility(INVISIBLE);
        FrameLayout.LayoutParams refreshSize = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.RIGHT);
        refreshSize.setMargins(0, dp(24), dp(28), 0);
        addView(refreshSpinner, refreshSize);

        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xf5090e17, 0xb0090e17, Color.TRANSPARENT}));
        scrim.setVisibility(GONE);
        addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        drawer = new TvNavigationDrawer(activity, scrim, this::focusBrowser);
        addView(drawer, new FrameLayout.LayoutParams(dp(TvNavigationDrawer.RAIL_WIDTH), -1, Gravity.LEFT));
        drawer.addDestination("Home", R.drawable.nav_house, () -> openCatalogue("https://gramaton.io/", "Home"));
        drawer.addDestination("Search", R.drawable.nav_search, this::showSearch);
        drawer.addDestination("Movies", R.drawable.nav_clapperboard, () -> openCatalogue("https://gramaton.io/movies", "Movies"));
        drawer.addDestination("TV", R.drawable.nav_tv_minimal, () -> openCatalogue("https://gramaton.io/series", "TV"));
        drawer.addDestination("Watchlist", R.drawable.nav_bookmark, () -> openCatalogue("https://gramaton.io/watchlist", "Watchlist"));
        drawer.addDestination("Favorites", R.drawable.nav_heart, () -> openCatalogue("https://gramaton.io/favorites", "Favorites"));
        drawer.separateActions();
        drawer.addDestination("Account", R.drawable.nav_circle_user_round, actions::account);
        drawer.addDestination("Updates", R.drawable.nav_refresh_cw, actions::checkForUpdates);
        drawer.select("Home");

        searchBar = new LinearLayout(activity); searchBar.setGravity(Gravity.CENTER_VERTICAL); searchBar.setPadding(0, dp(8), 0, dp(5));
        query = new EditText(activity); query.setSingleLine(true); query.setTextColor(WHITE); query.setHintTextColor(MUTED);
        query.setHint("Search movies and TV shows"); query.setTextSize(19); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setSelectAllOnFocus(true);
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button submit = button("Search"); submit.setOnClickListener(v -> submitSearch()); searchBar.addView(submit);
        searchBar.setVisibility(GONE); browserColumn.addView(searchBar);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) { submitSearch(); return true; }
            return false;
        });

        gridToolbar = new LinearLayout(activity);
        gridToolbar.setGravity(Gravity.CENTER_VERTICAL);
        gridToolbar.setPadding(dp(4), 0, 0, dp(10));
        gridTitle = text("", 25, WHITE);
        gridTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        gridToolbar.addView(gridTitle, new LinearLayout.LayoutParams(0, -2, 1));
        moreSpinner = new ProgressBar(activity);
        moreSpinner.setContentDescription("Loading more titles");
        moreSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff70b8ff));
        moreSpinner.setVisibility(INVISIBLE);
        gridToolbar.addView(moreSpinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        sortFilter = button("Sort");
        genreFilter = button("Genre");
        for (Button filter : new Button[]{sortFilter, genreFilter}) {
            LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(-2, dp(40));
            filterSize.leftMargin = dp(10);
            gridToolbar.addView(filter, filterSize);
            filter.setOnKeyListener((view, key, event) -> navigateGridFilter(filter, key, event));
        }
        gridToolbar.setVisibility(GONE);
        browserColumn.addView(gridToolbar, new LinearLayout.LayoutParams(-1, -2));

        hero = new FrameLayout(activity); hero.setBackground(round(BG, 0, 18)); hero.setClipToOutline(true);
        LinearLayout.LayoutParams heroSize = new LinearLayout.LayoutParams(-1, dp(174)); heroSize.topMargin = dp(10); heroSize.bottomMargin = dp(8); browserColumn.addView(hero, heroSize);
        heroImage = new ImageView(activity); heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP); heroImage.setAlpha(.78f);
        int artWidth = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density * .59f);
        hero.addView(heroImage, new FrameLayout.LayoutParams(artWidth, -1, Gravity.RIGHT));
        View shade = new View(activity); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BG, BG, 0xc0090e17, 0x15090e17})); hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(activity); bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00090e17, 0x00090e17, BG})); hero.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = new LinearLayout(activity); copy.setOrientation(LinearLayout.VERTICAL); copy.setGravity(Gravity.CENTER_VERTICAL); copy.setPadding(dp(3), dp(5), dp(15), dp(5));
        kicker = text("YOUR NEXT GREAT WATCH", 10, 0xff76baff); kicker.setLetterSpacing(.14f); copy.addView(kicker);
        title = text("Welcome to Gramaton", 28, WHITE); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); copy.addView(title);
        subtitle = text("Movies and TV, ready for your remote", 13, 0xffc0cbdc); subtitle.setMaxLines(1); subtitle.setEllipsize(TextUtils.TruncateAt.END); subtitle.setPadding(0, dp(4), 0, dp(3)); copy.addView(subtitle);
        description = text("", 14, MUTED); description.setMaxLines(2); description.setEllipsize(TextUtils.TruncateAt.END); copy.addView(description);
        hero.addView(copy, new FrameLayout.LayoutParams(Math.round(artWidth * 1.02f), -1, Gravity.LEFT));
        message = text("", 12, MUTED); message.setPadding(0, 0, 0, dp(6)); message.setVisibility(GONE); browserColumn.addView(message);

        content = new FrameLayout(activity); content.setClipChildren(true); content.setClipToPadding(true); content.setBackgroundColor(BG); browserColumn.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setBrowseInsets(true);
        scroller = new ScrollView(activity); scroller.setFillViewport(false); scroller.setClipToPadding(true); scroller.setClipChildren(true); scroller.setFocusable(false); scroller.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false); rows.setPadding(0, dp(4), 0, dp(12)); scroller.addView(rows); content.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        grid = new TvPosterGrid(activity, artwork, new TvPosterGrid.Actions() {
            @Override public void open(JSONObject movie) { rememberPage(); actions.open(movie); }
            @Override public void focusMenu() { drawer.focusSelected(); }
            @Override public void focusFilters() { if (!sortFilter.requestFocus()) genreFilter.requestFocus(); }
            @Override public void reachEnd() { if (moreFailed) retryPage.requestFocus(); else requestMore(); }
            @Override public void nearEnd() { requestMore(); }
        });
        grid.setVisibility(GONE);
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        retryPage = button("Retry loading more titles");
        retryPage.setVisibility(GONE);
        retryPage.setOnClickListener(view -> startMore(true));
        retryPage.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_UP || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            return grid.focusSelected();
        });
        FrameLayout.LayoutParams retryPageSize = new FrameLayout.LayoutParams(-2, dp(40), Gravity.BOTTOM | Gravity.RIGHT);
        retryPageSize.setMargins(0, 0, dp(30), dp(16));
        content.addView(retryPage, retryPageSize);
        details = new LinearLayout(activity); details.setOrientation(LinearLayout.VERTICAL); details.setVisibility(GONE); content.addView(details, new FrameLayout.LayoutParams(-1, -1));
        loadingPanel = new LinearLayout(activity); loadingPanel.setOrientation(LinearLayout.VERTICAL); loadingPanel.setGravity(Gravity.CENTER); loadingPanel.setBackgroundColor(BG); loadingPanel.setVisibility(GONE);
        spinner = new ProgressBar(activity); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff329aff)); loadingPanel.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        loadingTitle = text("Loading Gramaton…", 23, WHITE); loadingTitle.setGravity(Gravity.CENTER); loadingTitle.setPadding(0, dp(15), 0, dp(5)); loadingPanel.addView(loadingTitle);
        loadingStage = text("Connecting…", 15, MUTED); loadingStage.setGravity(Gravity.CENTER); loadingStage.setVisibility(GONE); loadingPanel.addView(loadingStage);
        retry = button("Try again"); retry.setOnClickListener(v -> actions.retry()); retry.setVisibility(GONE); loadingPanel.addView(retry, new LinearLayout.LayoutParams(-2, dp(42)));
        content.addView(loadingPanel, new FrameLayout.LayoutParams(-1, -1));
        getViewTreeObserver().addOnScrollChangedListener(this::queueArtwork); setFocusable(false);
    }
    private void setBrowseInsets(boolean browsing) {
        FrameLayout.LayoutParams columnSize = (FrameLayout.LayoutParams) browserColumn.getLayoutParams();
        columnSize.leftMargin = browsing ? dp(TvNavigationDrawer.RAIL_WIDTH) : 0;
        browserColumn.setLayoutParams(columnSize);
        browserColumn.setPadding(browsing ? dp(20) : 0, browsing ? dp(26) : 0, browsing ? dp(32) : 0, 0);
        LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) content.getLayoutParams();
        bounds.rightMargin = -browserColumn.getPaddingRight();
        content.setLayoutParams(bounds);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) { TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private GradientDrawable round(int fill, int border, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); if (border != 0) d.setStroke(dp(2), border); return d;
    }
    private Button button(String label) {
        Button b = new Button(activity); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(WHITE); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(7), dp(16), dp(7)); b.setBackground(round(0xff1b2638, 0, 20)); b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener((v, focus) -> { b.setTextColor(focus ? BG : WHITE); b.setBackground(round(focus ? WHITE : 0xff1b2638, 0, 20)); b.animate().scaleX(focus ? 1.035f : 1).scaleY(focus ? 1.035f : 1).setDuration(130).start(); });
        return b;
    }
    private void focusBrowser() {
        if (loadingPanel.getVisibility() == VISIBLE) {
            if (retry.getVisibility() == VISIBLE) retry.requestFocus();
        } else if (searchBar.getVisibility() == VISIBLE) query.requestFocus();
        else focusRememberedCard();
    }
    private void openCatalogue(String url, String label) {
        focusCatalogueWhenReady();
        actions.navigate(url, label);
    }
    void focusCatalogueWhenReady() { focusCatalogueOnRender = true; }
    boolean dismissDrawer() { return drawer.hasFocus() && focusRememberedCard(); }
    private boolean navigateGridFilter(Button filter, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) focusRememberedCard();
        else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (filter == sortFilter || !sortFilter.isEnabled()) drawer.focusSelected();
            else sortFilter.requestFocus();
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (filter == sortFilter) genreFilter.requestFocus();
        } else if (key != KeyEvent.KEYCODE_DPAD_UP) return false;
        return true;
    }
    static boolean isGridListing(String label) {
        return label.startsWith("Search: ") || label.equals("Watchlist") || label.equals("Favorites") || hasListingFilters(label);
    }
    private static boolean hasListingFilters(String label) {
        return switch (label) {
            case "Movies", "TV", "Recently Added", "Recent Episodes" -> true;
            default -> false;
        };
    }
    private void updateGridToolbarVisibility() {
        gridToolbar.setVisibility(gridMode && isGridListing(pageLabel) && !detailShowing && searchBar.getVisibility() != VISIBLE ? VISIBLE : GONE);
    }
    private void bindGridFilter(Button button, String title, String parameter, JSONObject filter) {
        JSONArray options = filter == null ? null : filter.optJSONArray("options");
        String current = filter == null ? "" : filter.optString("current");
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        int selected = -1;
        if (options != null) for (int i = 0; i < options.length(); i++) {
            JSONObject choice = options.optJSONObject(i);
            if (choice == null) continue;
            String value = choice.optString("value");
            if (current.equalsIgnoreCase(value)) selected = values.size();
            labels.add(choice.optString("label"));
            values.add(value);
        }
        int checked = selected;
        button.setText(checked < 0 ? title : title + " · " + labels.get(checked));
        button.setEnabled(!values.isEmpty());
        button.setVisibility(values.isEmpty() ? GONE : VISIBLE);
        button.setOnClickListener(view -> new android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                dialog.dismiss();
                if (which != checked) actions.filter(parameter, values.get(which));
            }).show());
    }
    private void selectDestination(String label) {
        String name = label.startsWith("Search:") ? "Search" : label.equals("Home") || label.equals("Favorites") || label.equals("Watchlist") ? label : label.equals("TV") || label.equals("Recent Episodes") ? "TV" : "Movies";
        drawer.select(name);
    }
    void setPageKey(String key) { if (!pageKey.equals(key)) { rememberPage(); pageKey = key; changingPage = true; } }
    void rememberPage() {
        if (changingPage || (gridMode ? !grid.hasPosters() : cards.isEmpty())) return;
        Snapshot state = new Snapshot();
        if (gridMode) {
            state.key = grid.selectedUrl();
            state.gridPosition = grid.firstVisiblePosition();
            state.gridOffset = grid.firstVisibleOffset();
        } else {
            state.key = focusedKey;
            state.y = scroller.getScrollY();
            for (Map.Entry<String, HorizontalScrollView> row : horizontalRows.entrySet()) state.x.put(row.getKey(), row.getValue().getScrollX());
        }
        snapshots.put(pageKey, state);
    }
    private void showCatalogueViews(boolean visible) {
        grid.setVisibility(visible && gridMode ? VISIBLE : GONE);
        scroller.setVisibility(visible && !gridMode ? VISIBLE : GONE);
        updateMoreStatus();
    }
    private void updateMoreStatus() {
        moreSpinner.setVisibility(moreLoading ? VISIBLE : INVISIBLE);
        retryPage.setVisibility(moreFailed && grid.getVisibility() == VISIBLE && !loading ? VISIBLE : GONE);
    }
    private void requestMore() {
        if (!moreFailed) startMore(false);
    }
    private void startMore(boolean retry) {
        if (!grid.isShown() || !moreAvailable || moreLoading || loading || searchBar.getVisibility() == VISIBLE) return;
        if (!actions.loadMore(retry)) return;
        if (retryPage.hasFocus()) grid.focusSelected();
        moreFailed = false;
        moreLoading = true;
        updateMoreStatus();
    }
    void suspendGridPaging() {
        moreLoading = false;
        updateMoreStatus();
    }
    void gridDocumentReady() { if (gridMode) grid.checkPagingSoon(); }
    void failGridPage() {
        moreLoading = false;
        moreFailed = true;
        updateMoreStatus();
    }
    void appendGridPage(JSONArray items, boolean hasNext) {
        int added = grid.append(items);
        moreAvailable = hasNext && added > 0;
        moreLoading = false;
        moreFailed = false;
        updateMoreStatus();
        if (moreAvailable) grid.checkPagingSoon();
    }
    void loading(String label) {
        hero.setVisibility(GONE);
        if (!detailShowing) { pageLabel = label; updateGridToolbarVisibility(); selectDestination(label); kicker.setText("GRAMATON"); title.setText(label); subtitle.setText("Movies and TV, made for your remote"); description.setText(""); showCatalogueViews(false); }
        message.setVisibility(GONE); refreshSpinner.setVisibility(INVISIBLE); loading = true; loadingStage.setVisibility(GONE);
        loadingTitle.setText("Loading " + label + "…"); spinner.setVisibility(VISIBLE); retry.setVisibility(GONE); loadingPanel.setVisibility(VISIBLE); loadingPanel.bringToFront();
    }
    void refreshing(String label) { pageLabel = label; selectDestination(label); refreshSpinner.setVisibility(VISIBLE); }
    void finishLoading() { loading = false; loadingPanel.setVisibility(GONE); refreshSpinner.setVisibility(INVISIBLE); }
    void status(String value) {
        if (loading) { loading = false; spinner.setVisibility(GONE); loadingTitle.setText("Couldn't finish loading"); loadingStage.setText(value); loadingStage.setVisibility(VISIBLE); retry.setVisibility(VISIBLE); }
        else { message.setText(value); message.setVisibility(VISIBLE); refreshSpinner.setVisibility(INVISIBLE); }
    }
    private void showSearch() { hideDetails(); drawer.select("Search"); searchBar.setVisibility(VISIBLE); updateGridToolbarVisibility(); query.requestFocus(); query.selectAll(); ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT); }
    private void submitSearch() { String q = query.getText().toString().trim(); if (q.isEmpty()) { query.setError("Enter a title"); return; } ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0); searchBar.setVisibility(GONE); drawer.focusDestination("Search"); focusCatalogueWhenReady(); actions.search(q); }
    void resetSignature() { signature = ""; }
    boolean hasRenderedCatalogue(String label) { return !signature.isEmpty() && !changingPage && renderedLabel.equals(label); }

    private JSONArray deviceWatchlist() {
        String saved = activity.getSharedPreferences("device_watchlist", 0).getString("titles", "[]");
        try { return new JSONArray(saved); }
        catch (org.json.JSONException error) { throw new IllegalStateException("Could not read saved titles", error); }
    }

    private String titlePath(JSONObject movie) {
        return android.net.Uri.parse(movie.optString("url")).getPath();
    }

    private int savedIndex(JSONArray titles, JSONObject movie) {
        for (int i = 0; i < titles.length(); i++) {
            if (java.util.Objects.equals(titlePath(titles.optJSONObject(i)), titlePath(movie))) return i;
        }
        return -1;
    }

    private Button watchlistButton(JSONObject movie) {
        Button save = button("");
        Runnable refresh = () -> save.setText(savedIndex(deviceWatchlist(), movie) >= 0
            ? "Remove from TV Watchlist" : "+ Watchlist");
        refresh.run();
        save.setOnClickListener(v -> {
            JSONArray titles = deviceWatchlist();
            int index = savedIndex(titles, movie);
            if (index >= 0) titles.remove(index);
            else titles.put(movie);
            activity.getSharedPreferences("device_watchlist", 0).edit().putString("titles", titles.toString()).apply();
            refresh.run();
            save.announceForAccessibility(index >= 0 ? "Title removed" : "Title saved");
        });
        return save;
    }

    void render(JSONObject data, String label) throws Exception {
        JSONArray items = data.getJSONArray("cards");
        if (label.equals("Home") || label.equals("Watchlist")) {
            JSONArray merged = new JSONArray();
            JSONArray saved = deviceWatchlist();
            for (int i = saved.length() - 1; i >= 0; i--) {
                JSONObject item = new JSONObject(saved.getJSONObject(i).toString());
                item.put("section", "Watchlist");
                item.put("landscape", false);
                merged.put(item);
            }
            JSONArray original = items;
            items = new JSONArray();
            boolean inserted = false;
            for (int i = 0; i < original.length(); i++) {
                JSONObject item = original.getJSONObject(i);
                if (!inserted && !item.optString("section").equals("Featured")) {
                    for (int j = 0; j < merged.length(); j++) items.put(merged.get(j));
                    inserted = true;
                }
                boolean watchlistRow = label.equals("Watchlist") || item.optString("section").equals("Watchlist");
                if (!watchlistRow || savedIndex(saved, item) < 0) items.put(item);
            }
            if (!inserted) for (int j = 0; j < merged.length(); j++) items.put(merged.get(j));
        }
        JSONArray links = data.optJSONArray("sections");
        JSONObject filters = hasListingFilters(label) ? data.optJSONObject("filters") : null;
        String newSignature = pageKey + label + items.toString() + links + filters + data.optBoolean("next");
        finishLoading(); selectDestination(label);
        if (newSignature.equals(signature)) {
            if (!detailShowing) {
                updateGridToolbarVisibility();
                showCatalogueViews(true);
                if (lastCard != null && lastCard.getTag() instanceof JSONObject movie) {
                    updateHero(movie, label.equals("Home") ? movie.optString("section", "Featured") : label);
                }
                if (focusCatalogueOnRender) focusRememberedCard();
            }
            focusCatalogueOnRender = false;
            return;
        }
        boolean restoreCardFocus = focusCatalogueOnRender || (gridMode ? grid.hasFocus() : lastCard != null && lastCard.hasFocus());
        focusCatalogueOnRender = false;
        message.setVisibility(GONE);
        if (!changingPage) rememberPage(); changingPage = false;
        Snapshot state = snapshots.get(pageKey); signature = newSignature; pageLabel = label;
        renderedLabel = label;
        gridMode = isGridListing(label);
        moreAvailable = gridMode && data.optBoolean("next");
        moreLoading = false;
        moreFailed = false;
        updateGridToolbarVisibility();
        lastCard = null; rows.removeAllViews(); cards.clear(); cardViews.clear(); horizontalRows.clear(); posters.clear();
        if (gridMode) {
            gridTitle.setText(label);
            bindGridFilter(sortFilter, "Sort", "sortby", filters == null ? null : filters.optJSONObject("sort"));
            bindGridFilter(genreFilter, "Genre", "genres", filters == null ? null : filters.optJSONObject("genre"));
            hero.setVisibility(GONE);
            grid.replace(items);
            grid.restore(state == null ? "" : state.key, state == null ? 0 : state.gridPosition, state == null ? 0 : state.gridOffset);
            showCatalogueViews(!detailShowing);
            if (grid.hasPosters()) {
                if (restoreCardFocus && !detailShowing && searchBar.getVisibility() != VISIBLE) grid.focusSelected();
            } else {
                message.setText(filters == null ? "No titles found." : "No titles match these filters.");
                message.setVisibility(VISIBLE);
                if (!gridToolbar.hasFocus() && !sortFilter.requestFocus()) drawer.focusSelected();
            }
            return;
        }
        showCatalogueViews(!detailShowing);
        grid.replace(new JSONArray());
        Map<String, String> sectionLinks = new HashMap<>();
        if (links != null) for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.getJSONObject(i);
            String url = link.optString("url");
            if (!url.isEmpty()) sectionLinks.put(link.optString("label"), url);
        }
        LinkedHashMap<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.getJSONObject(i); String group = label.equals("Home") ? item.optString("section", "Movies") : label; groups.computeIfAbsent(group, k -> new ArrayList<>()).add(item); }
        for (Map.Entry<String, List<JSONObject>> group : groups.entrySet()) {
            if (label.equals("Home")) addRow(group.getKey(), group.getValue(), sectionLinks.get(group.getKey()));
            else for (int start = 0; start < group.getValue().size(); start += 8) addRow(group.getKey() + (start == 0 ? "" : " · more"), group.getValue().subList(start, Math.min(start + 8, group.getValue().size())), null);
        }
        if (cards.isEmpty()) {
            kicker.setText("YOUR LIBRARY");
            title.setText(label);
            subtitle.setText("No matching titles");
            description.setText("Try another search or choose a section from the menu.");
            drawer.focusDestination("Search");
        }
        else {
            View remembered = state == null ? null : cardViews.get(state.key);
            View restoreTarget = remembered == null ? cards.get(0).get(0) : remembered;
            lastCard = restoreTarget;
            if (restoreTarget.getTag() instanceof JSONObject movie) {
                updateHero(movie, label.equals("Home") ? movie.optString("section", "Featured") : label);
            }
            main.post(() -> {
                if (detailShowing || !restoreTarget.isAttachedToWindow()) return;
                if (state != null) { for (Map.Entry<String, Integer> x : state.x.entrySet()) { HorizontalScrollView row = horizontalRows.get(x.getKey()); if (row != null) row.scrollTo(x.getValue(), 0); } scroller.scrollTo(0, state.y); }
                else scroller.scrollTo(0, 0);
                if (restoreCardFocus && searchBar.getVisibility() != VISIBLE) restoreTarget.requestFocus();
                queueArtwork();
            });
        }
    }
    private void addRow(String label, List<JSONObject> movies, String listingUrl) {
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(4), dp(3), dp(9), dp(3));
        TextView name = text(label, 18, WHITE); name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        rows.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(activity); horizontal.setHorizontalScrollBarEnabled(false); horizontal.setFocusable(false); horizontal.setClipToPadding(false); horizontalRows.put(label, horizontal);
        // Focus scaling extends into the row padding; keep that gutter drawable.
        LinearLayout line = new LinearLayout(activity); line.setClipChildren(false); line.setClipToPadding(false); line.setPadding(0, dp(9), dp(10), dp(33)); horizontal.addView(line); rows.addView(horizontal);
        addCells(line, horizontal, label, movies, listingUrl, Math.round(dp(198) * .75f), dp(13));
    }
    private void addCells(LinearLayout line, HorizontalScrollView horizontal, String label, List<JSONObject> movies,
                          String listingUrl, int posterWidth, int columnGap) {
        int featuredWidth = 2 * posterWidth + columnGap;
        int ring = dp(2);
        int posterHeight = Math.round((posterWidth - 4 * ring) * 1.5f);
        int featuredHeight = Math.round((featuredWidth - 4 * ring) * 9f / 16f);
        int row = cards.size(); List<View> cells = new ArrayList<>(); cards.add(cells);
        int count = movies.size() + (listingUrl != null ? 1 : 0);
        for (int i = 0; i < count; i++) {
            int column = i;
            boolean viewAll = column == movies.size();
            JSONObject movie = viewAll ? null : movies.get(column);
            String key = label + "|" + (viewAll ? "view-all" : movie.optString("url"));
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setTag(movie);
            card.setFocusableInTouchMode(true);
            card.setDefaultFocusHighlightEnabled(false);
            boolean wide = (viewAll ? movies.get(column - 1) : movie).optBoolean("landscape");
            int artworkHeight = wide ? featuredHeight : posterHeight;
            if (viewAll) {
                TextView tile = text("View all", 17, WHITE);
                tile.setGravity(Gravity.CENTER);
                tile.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                tile.setBackground(round(0xff1c293b, 0, 7));
                tile.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                card.addView(tile, new LinearLayout.LayoutParams(-1, artworkHeight));
                card.setContentDescription("View all " + label);
                card.setOnClickListener(v -> openCatalogue(listingUrl, label));
            } else {
                card.setContentDescription(movie.optString("title") + ". " + movie.optString("meta"));
                addPoster(card, movie, artworkHeight, pageLabel.equals("Home") && label.equals("Featured"));
                card.setOnClickListener(v -> { rememberPage(); actions.open(movie); });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wide ? featuredWidth : posterWidth, -2);
            lp.rightMargin = columnGap;
            line.addView(card, lp);
            cells.add(card);
            cardViews.put(key, card);
            // Reserve the ring space so focus changes never resize the artwork.
            card.setPadding(2 * ring, 2 * ring, 2 * ring, 2 * ring);
            android.graphics.drawable.LayerDrawable focusFrame = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{round(Color.WHITE, 0, 11), round(Color.BLACK, 0, 9)});
            focusFrame.setLayerInset(1, ring, ring, ring, ring);
            card.setOnFocusChangeListener((v, focus) -> {
                v.setBackground(focus ? focusFrame : null);
                v.setPadding(2 * ring, 2 * ring, 2 * ring, 2 * ring);
                v.setPivotX(0);
                v.animate().translationX(focus && !wide ? 2 * ring : 0).scaleX(focus && !wide ? 1.055f : 1).scaleY(focus && !wide ? 1.055f : 1).translationZ(focus ? dp(4) : 0).setDuration(140).start();
                if (focus) {
                    if (pageLabel.equals("Home")) homeSelections.put(label, key);
                    lastCard = card;
                    focusedKey = key;
                    if (viewAll) hero.setVisibility(GONE);
                    else updateHero(movie, label);
                    v.requestRectangleOnScreen(new Rect(-dp(7), -dp(30), v.getWidth() + dp(7), v.getHeight() + dp(8)), false);
                    horizontal.smoothScrollTo(Math.max(0, v.getLeft()), 0);
                    for (int near = Math.max(0, column - 1); near <= Math.min(movies.size() - 1, column + 2); near++) {
                        ImageView nearby = (ImageView) cells.get(near).getTag(R.id.poster_image);
                        artwork.load(movies.get(near).optString("image"), nearby, false);
                    }
                    queueArtwork();
                }
            });
            card.setOnKeyListener((v, keyCode, e) -> navigateCell(keyCode, e, row, column));
        }
    }

    private void addPoster(LinearLayout card, JSONObject movie, int artworkHeight, boolean featured) {
        ImageView poster = new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(round(0xff1c293b, 0, 7));
        poster.setClipToOutline(true);
        poster.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.setTag(R.id.poster_image, poster);
        if (featured) {
            FrameLayout cover = new FrameLayout(activity);
            cover.setBackground(round(0xff1c293b, 0, 7));
            cover.setClipToOutline(true);
            cover.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            TextView lettering = text(movie.optString("title"), 17, WHITE);
            lettering.setGravity(Gravity.BOTTOM | Gravity.LEFT);
            lettering.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            lettering.setMaxLines(2);
            lettering.setEllipsize(TextUtils.TruncateAt.END);
            lettering.setPadding(dp(11), dp(12), dp(11), dp(7));
            lettering.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            lettering.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0xd9000000}));
            cover.addView(lettering, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            card.addView(cover, new LinearLayout.LayoutParams(-1, artworkHeight));
        } else {
            card.addView(poster, new LinearLayout.LayoutParams(-1, artworkHeight));
        }
        posters.add(new Poster(movie.optString("image"), poster));
    }
    private boolean navigateCell(int key, KeyEvent event, int row, int column) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int target = column + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (target < 0) drawer.focusSelected();
            else if (target < cards.get(row).size()) cards.get(row).get(target).requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int target = row + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (target < 0) return true;
            if (target < cards.size()) {
                List<View> nextRow = cards.get(target);
                boolean enteringHomeRow = key == KeyEvent.KEYCODE_DPAD_DOWN && pageLabel.equals("Home");
                int nextColumn = enteringHomeRow ? 0 : Math.min(column, nextRow.size() - 1);
                View destination = nextRow.get(nextColumn);
                if (pageLabel.equals("Home") && key == KeyEvent.KEYCODE_DPAD_UP) {
                    JSONObject first = (JSONObject) nextRow.get(0).getTag();
                    View remembered = cardViews.get(homeSelections.get(first.optString("section", "Movies")));
                    destination = remembered != null && nextRow.contains(remembered) ? remembered : nextRow.get(0);
                }
                destination.requestFocus();
            }
            return true;
        } return false;
    }
    private void updateHero(JSONObject movie, String row) {
        if (gridMode) { hero.setVisibility(GONE); return; }
        hero.setVisibility(detailShowing || !pageLabel.equals("Home") ? VISIBLE : GONE);
        selectedMovie = movie; kicker.setText(row.toUpperCase(java.util.Locale.ROOT)); title.setText(movie.optString("title")); subtitle.setText(movie.optString("meta"));
        String synopsis = movie.optString("description"); description.setText(synopsis.isEmpty() ? (movie.optBoolean("series") ? "Explore the series and choose an episode." : "Select to explore this movie and pick up where you left off.") : synopsis);
        main.removeCallbacks(heroArt); main.postDelayed(heroArt, 130);
    }
    private final Runnable heroArt = this::loadHeroArtwork;
    private void loadHeroArtwork() { if (selectedMovie == null) return; String url = selectedMovie.optString("image"); if (!url.equals(heroUrl)) { heroUrl = url; artwork.load(url, heroImage, true); } }
    private void queueArtwork() {
        if (artworkQueued) return; artworkQueued = true;
        main.postDelayed(() -> { artworkQueued = false; Rect visible = new Rect(); for (Poster poster : posters) if (poster.view.isShown() && poster.view.getGlobalVisibleRect(visible) && visible.width() > 12 && visible.height() > 12) artwork.load(poster.url, poster.view, false); }, 80);
    }
    private void addDetailButton(LinearLayout controls, Button button) {
        button.setPadding(dp(24), dp(8), dp(24), dp(8));
        button.setGravity(Gravity.CENTER);
        button.setOnFocusChangeListener((view, focused) -> {
            button.setTextColor(focused ? BG : WHITE);
            button.setBackground(round(focused ? WHITE : 0xff1b2638, 0, 22));
        });
        button.setBackground(round(0xff1b2638, 0, 22));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(44));
        if (controls.getChildCount() > 0) size.leftMargin = dp(12);
        controls.addView(button, size);
    }

    void showDetails(JSONObject movie) {
        rememberPage(); detailShowing = true; searchBar.setVisibility(GONE); showCatalogueViews(false); details.setVisibility(VISIBLE); details.removeAllViews();
        hero.setVisibility(GONE);
        updateGridToolbarVisibility();
        drawer.setVisibility(GONE);
        setBrowseInsets(false);
        if (detailArt != null) content.removeView(detailArt);
        detailArt = new FrameLayout(activity);
        content.addView(detailArt, 0, new FrameLayout.LayoutParams(-1, -1));
        detailPoster = new ImageView(activity);
        detailPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        detailPoster.setPadding(dp(32), dp(32), dp(32), dp(32));
        detailArt.addView(detailPoster, new FrameLayout.LayoutParams(-1, -1));
        detailBackdrop = new ImageView(activity);
        detailBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        detailArt.addView(detailBackdrop, new FrameLayout.LayoutParams(-1, -1));
        detailArt.setTag(movie);
        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xf0090e17, 0x85090e17, 0x00090e17}));
        detailArt.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        details.setGravity(Gravity.BOTTOM);
        details.setPadding(dp(36), dp(20), dp(36), dp(24));
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        int copyWidth = getResources().getDisplayMetrics().widthPixels / 3;
        TextView detailTitle = text(movie.optString("title"), 30, WHITE);
        detailTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        detailTitle.setMaxLines(2);
        detailTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(copyWidth, -2);
        titleSize.bottomMargin = dp(8);
        info.addView(detailTitle, titleSize);

        TextView metadata = text("", 14, WHITE);
        metadata.setTag("detail-metadata");
        metadata.setMaxLines(2);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        metadata.setPadding(0, 0, 0, dp(8));
        metadata.setVisibility(GONE);
        metadata.setTag(R.id.catalogue_metadata, movie.optString("meta"));
        info.addView(metadata, new LinearLayout.LayoutParams(copyWidth, -2));
        TextView synopsis = text("", 16, 0xffc1ccdc); synopsis.setLineSpacing(0, 1.2f); synopsis.setMaxLines(5); synopsis.setEllipsize(TextUtils.TruncateAt.END); synopsis.setTag("synopsis"); info.addView(synopsis, new LinearLayout.LayoutParams(copyWidth, -2));
        LinearLayout controls = new LinearLayout(activity); controls.setTag("actions"); controls.setGravity(Gravity.CENTER_VERTICAL);
        Button play = button("Play");
        play.setTag("play");
        play.setEnabled(false);
        play.setAlpha(.4f);
        play.setOnClickListener(v -> actions.play());
        addDetailButton(controls, play);
        addDetailButton(controls, watchlistButton(movie));
        LinearLayout.LayoutParams controlsSize = new LinearLayout.LayoutParams(-1, -2);
        controlsSize.topMargin = dp(16);
        info.addView(controls, controlsSize);
        details.addView(info);
        loading(movie.optString("title", "title details"));
    }
    void detailsReady(String backdrop, String poster, String synopsis, String year, String genre, String cast, long resumePosition, JSONArray episodes) {
        if (!detailShowing) return; finishLoading(); hero.setVisibility(GONE); message.setVisibility(GONE);
        if (detailBackdrop.getTag() == null && detailPoster.getTag() == null) {
            JSONObject movie = (JSONObject) detailArt.getTag();
            boolean catalogLandscape = movie.optBoolean("landscape");
            String landscapeUrl = backdrop.isEmpty() && catalogLandscape ? movie.optString("image") : backdrop;
            String portraitUrl = poster.isEmpty() && !catalogLandscape ? movie.optString("image") : poster;
            ImageView portrait = detailPoster;
            artwork.load(landscapeUrl, detailBackdrop, true, () -> {
                if (detailShowing && detailPoster == portrait) artwork.load(portraitUrl, portrait, true);
            });
        }
        TextView metadata = details.findViewWithTag("detail-metadata");
        List<String> parts = new ArrayList<>();
        String catalogueMeta = (String) metadata.getTag(R.id.catalogue_metadata);
        for (String part : catalogueMeta.split(" · ")) {
            if (year.isEmpty() && part.matches("(18|19|20)\\d{2}")) year = part;
            else if (genre.isEmpty() && !part.isEmpty() && !part.matches("(18|19|20)\\d{2}|TV series|S\\d+ E\\d+")) genre = part;
        }
        if (!year.isEmpty()) parts.add(year);
        if (!genre.isEmpty()) parts.add(genre);
        if (!cast.isEmpty()) parts.add("Starring " + cast);
        metadata.setText(TextUtils.join(" · ", parts));
        metadata.setVisibility(parts.isEmpty() ? GONE : VISIBLE);
        TextView copy = details.findViewWithTag("synopsis"); copy.setText(synopsis);
        Button play = details.findViewWithTag("play"); boolean firstReady = !play.isEnabled(); play.setEnabled(true); play.setAlpha(1); updateResumePosition(resumePosition);
        LinearLayout controls = details.findViewWithTag("actions"); View old = controls.findViewWithTag("episodes");
        if (old == null && episodes != null && episodes.length() > 0) {
            Button choose = button("Episodes"); choose.setTag("episodes"); choose.setOnClickListener(v -> chooseSeason(episodes)); addDetailButton(controls, choose);
        }
        if (firstReady && !drawer.hasFocus()) play.requestFocus();
    }
    void updateResumePosition(long position) {
        Button play = details.findViewWithTag("play");
        if (play == null) return;
        long seconds = Math.max(0, position / 1000);
        String time = seconds >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60) : String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        play.setText(position > 0 ? "▶  Resume at " + time : "Play");
    }
    private void chooseSeason(JSONArray episodes) {
        LinkedHashSet<Integer> seasons = new LinkedHashSet<>(); for (int i = 0; i < episodes.length(); i++) seasons.add(episodes.optJSONObject(i).optInt("season"));
        Integer[] numbers = seasons.toArray(new Integer[0]); String[] labels = new String[numbers.length]; for (int i = 0; i < labels.length; i++) labels[i] = "Season " + numbers[i];
        new android.app.AlertDialog.Builder(activity).setTitle("Choose a season").setItems(labels, (dialog, which) -> {
            List<JSONObject> list = new ArrayList<>(); for (int i = 0; i < episodes.length(); i++) if (episodes.optJSONObject(i).optInt("season") == numbers[which]) list.add(episodes.optJSONObject(i));
            String[] names = new String[list.size()]; for (int i = 0; i < names.length; i++) names[i] = list.get(i).optInt("episode") + " · " + list.get(i).optString("title");
            new android.app.AlertDialog.Builder(activity).setTitle("Season " + numbers[which]).setItems(names, (episodesDialog, selection) -> { JSONObject item = list.get(selection); actions.episode(item.optInt("season"), item.optInt("episode")); }).setNegativeButton("Back", (d, w) -> chooseSeason(episodes)).show();
        }).setNegativeButton("Cancel", null).show();
    }
    boolean isDetailShowing() { return detailShowing; }
    void hideDetails() { boolean restore = detailShowing && !drawer.hasFocus(); finishLoading(); detailShowing = false; if (detailArt != null) { content.removeView(detailArt); detailArt = null; detailPoster = null; detailBackdrop = null; } drawer.setVisibility(VISIBLE); setBrowseInsets(true); details.setVisibility(GONE); updateGridToolbarVisibility(); showCatalogueViews(true); queueArtwork(); if (restore) focusRememberedCard(); }
    void restoreFocus() { if (detailShowing) { View play = details.findViewWithTag("play"); if (play != null) play.requestFocus(); } else focusRememberedCard(); }
    private boolean focusRememberedCard() {
        if (gridMode) return grid.focusSelected();
        if (lastCard != null && lastCard.getParent() != null) return lastCard.requestFocus();
        return !cards.isEmpty() && !cards.get(0).isEmpty() && cards.get(0).get(0).requestFocus();
    }
    boolean closeSearch() {
        if (searchBar.getVisibility() != VISIBLE) return false;
        searchBar.setVisibility(GONE);
        updateGridToolbarVisibility();
        if (!drawer.hasFocus()) drawer.focusDestination("Search");
        return true;
    }
    void trimArtwork() {
        artwork.trimCache();
        Rect visible = new Rect();
        for (Poster poster : posters) {
            if (!poster.view.isShown() || !poster.view.getGlobalVisibleRect(visible)) {
                poster.view.setImageDrawable(null);
                poster.view.setTag(null);
            }
        }
    }
    void dispose() { main.removeCallbacksAndMessages(null); artwork.close(); }
}
