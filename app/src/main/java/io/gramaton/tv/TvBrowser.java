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
final class TvBrowser extends LinearLayout {
    interface Actions {
        void navigate(String url, String label); void open(JSONObject movie); void play();
        void search(String query); void page(String direction); void account(); void checkForUpdates(); void retry(); void exitToFireTv();
        void episode(int season, int episode);
    }
    private static final int BG = 0xff090e17, WHITE = 0xfff5f7fb, MUTED = 0xffaebbd0;
    private final Activity activity;
    private final Actions actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TvImageLoader artwork = new TvImageLoader();
    private final LinearLayout nav, searchBar, rows, details, loadingPanel;
    private final FrameLayout content, hero;
    private final ImageView heroImage;
    private FrameLayout detailArt;
    private ImageView detailPoster, detailBackdrop;
    private final TextView title, subtitle, description, kicker, message, loadingTitle, loadingStage;
    private final ProgressBar spinner, refreshSpinner;
    private final EditText query;
    private final ScrollView scroller;
    private final Button retry, searchTab;
    private final List<Button> tabs = new ArrayList<>();
    private Button activeTab, previousPage, nextPage;
    private final List<List<View>> cards = new ArrayList<>();
    private final Map<String, View> cardViews = new HashMap<>();
    private final Map<String, String> sectionLinks = new HashMap<>();
    private final Map<String, HorizontalScrollView> horizontalRows = new LinkedHashMap<>();
    private final List<Poster> posters = new ArrayList<>();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, String> homeSelections = new HashMap<>();
    private View lastCard;
    private boolean detailShowing, loading, artworkQueued, changingPage;
    private String signature = "", pageLabel = "Home", pageKey = "https://gramaton.io/", focusedKey = "";
    private String heroUrl = "";
    private JSONObject selectedMovie;
    private static final class Poster {
        final String url; final ImageView view;
        Poster(String url, ImageView view) { this.url = url; this.view = view; }
    }
    private static final class Snapshot {
        String key; int y;
        final Map<String, Integer> x = new HashMap<>();
    }

    TvBrowser(Activity activity, Actions actions) {
        super(activity); this.activity = activity; this.actions = actions;
        setOrientation(VERTICAL); setBackgroundColor(BG); setClipChildren(false); setClipToPadding(false);
        setPadding(dp(48), dp(26), dp(48), 0);
        nav = new LinearLayout(activity); nav.setPadding(dp(4), 0, 0, 0); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setClipChildren(false);
        ImageView brand = new ImageView(activity); brand.setImageResource(R.drawable.tv_icon);
        LinearLayout.LayoutParams logo = new LinearLayout.LayoutParams(dp(30), dp(30)); logo.rightMargin = dp(9); nav.addView(brand, logo);
        TextView wordmark = text("Gramaton", 18, WHITE); wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); nav.addView(wordmark);
        FrameLayout update = new FrameLayout(activity); refreshSpinner = new ProgressBar(activity);
        refreshSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff70b8ff)); refreshSpinner.setVisibility(INVISIBLE);
        update.addView(refreshSpinner, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)); nav.addView(update, new LinearLayout.LayoutParams(dp(26), dp(30)));
        nav.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        activeTab = addTab("Home", () -> actions.navigate("https://gramaton.io/", "Home"));
        searchTab = addTab("Search", this::showSearch);
        addTab("Movies", () -> actions.navigate("https://gramaton.io/movies", "Movies"));
        addTab("TV", () -> actions.navigate("https://gramaton.io/series", "TV"));
        addTab("Watchlist", () -> actions.navigate("https://gramaton.io/watchlist", "Watchlist"));
        addTab("Favorites", () -> actions.navigate("https://gramaton.io/favorites", "Favorites"));
        addTab("Account", actions::account);
        Button updates = addTab("Updates", actions::checkForUpdates);
        updates.setContentDescription("Check for updates");
        updates.setOnClickListener(v -> actions.checkForUpdates());
        addView(nav, new LinearLayout.LayoutParams(-1, dp(42))); updateTabs();

        searchBar = new LinearLayout(activity); searchBar.setGravity(Gravity.CENTER_VERTICAL); searchBar.setPadding(0, dp(8), 0, dp(5));
        query = new EditText(activity); query.setSingleLine(true); query.setTextColor(WHITE); query.setHintTextColor(MUTED);
        query.setHint("Search movies and TV shows"); query.setTextSize(19); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setSelectAllOnFocus(true);
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button submit = button("Search"); submit.setOnClickListener(v -> submitSearch()); searchBar.addView(submit);
        searchBar.setVisibility(GONE); addView(searchBar);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) { submitSearch(); return true; }
            return false;
        });

        hero = new FrameLayout(activity); hero.setBackground(round(BG, 0, 18)); hero.setClipToOutline(true);
        LinearLayout.LayoutParams heroSize = new LinearLayout.LayoutParams(-1, dp(174)); heroSize.topMargin = dp(10); heroSize.bottomMargin = dp(8); addView(hero, heroSize);
        heroImage = new ImageView(activity); heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP); heroImage.setAlpha(.78f);
        int artWidth = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density * .59f);
        hero.addView(heroImage, new FrameLayout.LayoutParams(artWidth, -1, Gravity.RIGHT));
        View shade = new View(activity); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BG, BG, 0xc0090e17, 0x15090e17})); hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(activity); bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00090e17, 0x00090e17, BG})); hero.addView(bottom, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = new LinearLayout(activity); copy.setOrientation(VERTICAL); copy.setGravity(Gravity.CENTER_VERTICAL); copy.setPadding(dp(3), dp(5), dp(15), dp(5));
        kicker = text("YOUR NEXT GREAT WATCH", 10, 0xff76baff); kicker.setLetterSpacing(.14f); copy.addView(kicker);
        title = text("Welcome to Gramaton", 28, WHITE); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); copy.addView(title);
        subtitle = text("Movies and TV, ready for your remote", 13, 0xffc0cbdc); subtitle.setMaxLines(1); subtitle.setEllipsize(TextUtils.TruncateAt.END); subtitle.setPadding(0, dp(4), 0, dp(3)); copy.addView(subtitle);
        description = text("", 14, MUTED); description.setMaxLines(2); description.setEllipsize(TextUtils.TruncateAt.END); copy.addView(description);
        hero.addView(copy, new FrameLayout.LayoutParams(Math.round(artWidth * 1.02f), -1, Gravity.LEFT));
        message = text("", 12, MUTED); message.setPadding(0, 0, 0, dp(6)); message.setVisibility(GONE); addView(message);

        content = new FrameLayout(activity); content.setClipChildren(true); content.setClipToPadding(true); content.setBackgroundColor(BG); addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        updateContentEdges();
        scroller = new ScrollView(activity); scroller.setFillViewport(false); scroller.setClipToPadding(true); scroller.setClipChildren(true); scroller.setFocusable(false); scroller.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(activity); rows.setOrientation(VERTICAL); rows.setClipChildren(false); rows.setPadding(0, dp(4), 0, dp(12)); scroller.addView(rows); content.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        details = new LinearLayout(activity); details.setOrientation(VERTICAL); details.setVisibility(GONE); content.addView(details, new FrameLayout.LayoutParams(-1, -1));
        loadingPanel = new LinearLayout(activity); loadingPanel.setOrientation(VERTICAL); loadingPanel.setGravity(Gravity.CENTER); loadingPanel.setBackgroundColor(BG); loadingPanel.setVisibility(GONE);
        spinner = new ProgressBar(activity); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff329aff)); loadingPanel.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        loadingTitle = text("Loading Gramaton…", 23, WHITE); loadingTitle.setGravity(Gravity.CENTER); loadingTitle.setPadding(0, dp(15), 0, dp(5)); loadingPanel.addView(loadingTitle);
        loadingStage = text("Connecting…", 15, MUTED); loadingStage.setGravity(Gravity.CENTER); loadingStage.setVisibility(GONE); loadingPanel.addView(loadingStage);
        retry = button("Try again"); retry.setOnClickListener(v -> actions.retry()); retry.setVisibility(GONE); loadingPanel.addView(retry, new LinearLayout.LayoutParams(-2, dp(42)));
        content.addView(loadingPanel, new FrameLayout.LayoutParams(-1, -1));
        getViewTreeObserver().addOnScrollChangedListener(this::queueArtwork); setFocusable(false);
    }
    private void updateContentEdges() {
        LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) content.getLayoutParams();
        bounds.rightMargin = -getPaddingRight();
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
    private Button addTab(String label, Runnable action) {
        Button b = button(label); b.setPadding(dp(11), dp(6), dp(11), dp(6)); tabs.add(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36)); lp.setMargins(dp(2), 0, dp(2), 0); nav.addView(b, lp);
        b.setOnFocusChangeListener((v, focus) -> updateTabs());
        b.setOnClickListener(v -> { activeTab = b; updateTabs(); action.run(); });
        b.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (loadingPanel.getVisibility() == VISIBLE) { if (retry.getVisibility() == VISIBLE) retry.requestFocus(); }
            else if (detailShowing) { View play = details.findViewWithTag("play"); if (play != null && play.isEnabled()) play.requestFocus(); }
            else if (searchBar.getVisibility() == VISIBLE) query.requestFocus(); else focusRememberedCard();
            return true;
        }); return b;
    }
    private void updateTabs() {
        for (Button b : tabs) { boolean focus = b.hasFocus(); b.setTextColor(focus ? BG : b == activeTab ? WHITE : MUTED); b.setBackground(round(focus ? WHITE : b == activeTab ? 0xff22334c : Color.TRANSPARENT, 0, 18)); }
    }
    private void selectTab(String label) {
        String name = label.startsWith("Search:") ? "Search" : label.equals("Home") || label.equals("Favorites") || label.equals("Watchlist") ? label : label.equals("TV") || label.equals("Recent Episodes") ? "TV" : "Movies";
        for (Button b : tabs) if (b.getText().toString().equals(name)) activeTab = b; updateTabs();
    }
    void setPageKey(String key) { if (!pageKey.equals(key)) { rememberPage(); pageKey = key; changingPage = true; } }
    void rememberPage() {
        if (changingPage || cards.isEmpty()) return;
        Snapshot state = new Snapshot(); state.key = focusedKey; state.y = scroller.getScrollY();
        for (Map.Entry<String, HorizontalScrollView> row : horizontalRows.entrySet()) state.x.put(row.getKey(), row.getValue().getScrollX());
        snapshots.put(pageKey, state);
    }
    void loading(String label) {
        hero.setVisibility(GONE);
        if (!detailShowing) { pageLabel = label; selectTab(label); kicker.setText("GRAMATON"); title.setText(label); subtitle.setText("Movies and TV, made for your remote"); description.setText(""); scroller.setVisibility(INVISIBLE); }
        message.setVisibility(GONE); refreshSpinner.setVisibility(INVISIBLE); loading = true; loadingStage.setVisibility(GONE);
        loadingTitle.setText("Loading " + label + "…"); spinner.setVisibility(VISIBLE); retry.setVisibility(GONE); loadingPanel.setVisibility(VISIBLE); loadingPanel.bringToFront();
    }
    void refreshing(String label) { pageLabel = label; selectTab(label); refreshSpinner.setVisibility(VISIBLE); }
    void finishLoading() { loading = false; loadingPanel.setVisibility(GONE); refreshSpinner.setVisibility(INVISIBLE); }
    void status(String value) {
        if (loading) { loading = false; spinner.setVisibility(GONE); loadingTitle.setText("Couldn't finish loading"); loadingStage.setText(value); loadingStage.setVisibility(VISIBLE); retry.setVisibility(VISIBLE); }
        else { message.setText(value); message.setVisibility(VISIBLE); refreshSpinner.setVisibility(INVISIBLE); }
    }
    private void showSearch() { hideDetails(); activeTab = searchTab; updateTabs(); searchBar.setVisibility(VISIBLE); query.requestFocus(); query.selectAll(); ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT); }
    private void submitSearch() { String q = query.getText().toString().trim(); if (q.isEmpty()) { query.setError("Enter a title"); return; } ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0); searchBar.setVisibility(GONE); searchTab.requestFocus(); actions.search(q); }
    void resetSignature() { signature = ""; }

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
        String newSignature = pageKey + label + items.toString() + data.optBoolean("next") + data.optBoolean("previous");
        finishLoading(); message.setVisibility(GONE); selectTab(label);
        if (newSignature.equals(signature)) { if (!detailShowing) { scroller.setVisibility(VISIBLE); if (lastCard != null && lastCard.getTag() instanceof JSONObject) updateHero((JSONObject)lastCard.getTag(), label.equals("Home") ? ((JSONObject)lastCard.getTag()).optString("section", "Featured") : label); } return; }
        boolean hadCardFocus = lastCard != null && lastCard.hasFocus();
        if (!changingPage) rememberPage(); changingPage = false;
        Snapshot state = snapshots.get(pageKey); signature = newSignature; pageLabel = label;
        lastCard = null; rows.removeAllViews(); cards.clear(); cardViews.clear(); horizontalRows.clear(); posters.clear();
        if (!detailShowing) scroller.setVisibility(VISIBLE);
        sectionLinks.clear(); JSONArray links = data.optJSONArray("sections");
        if (links != null) for (int i = 0; i < links.length(); i++) { JSONObject link = links.getJSONObject(i); sectionLinks.put(link.optString("label"), link.optString("url")); }
        LinkedHashMap<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.getJSONObject(i); String group = label.equals("Home") ? item.optString("section", "Movies") : label; groups.computeIfAbsent(group, k -> new ArrayList<>()).add(item); }
        for (Map.Entry<String, List<JSONObject>> group : groups.entrySet()) {
            if (label.equals("Home")) addRow(group.getKey(), group.getValue());
            else for (int start = 0; start < group.getValue().size(); start += 8) addRow(group.getKey() + (start == 0 ? "" : " · more"), group.getValue().subList(start, Math.min(start + 8, group.getValue().size())));
        }
        LinearLayout paging = new LinearLayout(activity); paging.setPadding(dp(10), dp(10), 0, dp(8)); previousPage = null; nextPage = null;
        if (data.optBoolean("previous")) { previousPage = button("Previous page"); previousPage.setOnClickListener(v -> actions.page("prev")); paging.addView(previousPage); }
        if (data.optBoolean("next")) { nextPage = button("Next page →"); nextPage.setOnClickListener(v -> actions.page("next")); paging.addView(nextPage); }
        for (Button page : new Button[]{previousPage, nextPage}) if (page != null) page.setOnKeyListener((v, key, e) -> { if (key == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN) { focusRememberedCard(); return true; } return false; });
        rows.addView(paging);
        if (cards.isEmpty()) { kicker.setText("YOUR LIBRARY"); title.setText(label); subtitle.setText("No matching titles"); description.setText("Try another search or choose a section above."); searchTab.requestFocus(); }
        else {
            View remembered = state == null ? null : cardViews.get(state.key); lastCard = remembered == null ? cards.get(0).get(0) : remembered;
            if (lastCard.getTag() instanceof JSONObject) updateHero((JSONObject)lastCard.getTag(), label.equals("Home") ? ((JSONObject)lastCard.getTag()).optString("section", "Featured") : label);
            main.post(() -> {
                if (detailShowing) return;
                if (state != null) { for (Map.Entry<String, Integer> x : state.x.entrySet()) { HorizontalScrollView row = horizontalRows.get(x.getKey()); if (row != null) row.scrollTo(x.getValue(), 0); } scroller.scrollTo(0, state.y); }
                else scroller.scrollTo(0, 0);
                if (hadCardFocus && searchBar.getVisibility() != VISIBLE) focusRememberedCard();
                queueArtwork();
            });
        }
    }
    private void addRow(String label, List<JSONObject> movies) {
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(4), dp(3), dp(9), dp(3));
        TextView name = text(label, 18, WHITE); name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        rows.addView(heading);
        HorizontalScrollView horizontal = new HorizontalScrollView(activity); horizontal.setHorizontalScrollBarEnabled(false); horizontal.setFocusable(false); horizontal.setClipToPadding(false); horizontalRows.put(label, horizontal);
        // Focus scaling extends into the row padding; keep that gutter drawable.
        LinearLayout line = new LinearLayout(activity); line.setClipChildren(false); line.setClipToPadding(false); line.setPadding(0, dp(9), dp(10), dp(33)); horizontal.addView(line); rows.addView(horizontal);
        float thumbnailScale = .75f;
        int posterWidth = Math.round(dp(198) * thumbnailScale);
        int columnGap = dp(13);
        int featuredWidth = 2 * posterWidth + columnGap;
        int ring = dp(2);
        int posterHeight = Math.round((posterWidth - 4 * ring) * 1.5f);
        int featuredHeight = Math.round((featuredWidth - 4 * ring) * 9f / 16f);
        int row = cards.size(); List<View> cells = new ArrayList<>(); cards.add(cells);
        for (int i = 0; i < movies.size(); i++) {
            JSONObject movie = movies.get(i); int column = i; String key = label + "|" + movie.optString("url");
            LinearLayout card = new LinearLayout(activity); card.setOrientation(VERTICAL); card.setTag(movie);
            card.setFocusableInTouchMode(true); card.setClickable(true); card.setDefaultFocusHighlightEnabled(false); card.setContentDescription(movie.optString("title") + ". " + movie.optString("meta"));
            boolean wide = movie.optBoolean("landscape");
            boolean featured = pageLabel.equals("Home") && label.equals("Featured");
            int artworkHeight = wide ? featuredHeight : posterHeight;
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wide ? featuredWidth : posterWidth, -2); lp.rightMargin = columnGap; line.addView(card, lp); cells.add(card); cardViews.put(key, card); posters.add(new Poster(movie.optString("image"), poster));
            card.setOnClickListener(v -> { rememberPage(); actions.open(movie); });
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
                    lastCard = card; focusedKey = key; updateHero(movie, label);
                    v.requestRectangleOnScreen(new Rect(-dp(7), -dp(30), v.getWidth() + dp(7), v.getHeight() + dp(8)), false);
                    horizontal.smoothScrollTo(Math.max(0, v.getLeft()), 0);
                    for (int near = Math.max(0, column - 1); near <= Math.min(cells.size() - 1, column + 2); near++) {
                        View cell = cells.get(near); if (cell instanceof LinearLayout) artwork.load(movies.get(near).optString("image"), (ImageView)cell.getTag(R.id.poster_image), false);
                    }
                    queueArtwork();
                }
            });
            card.setOnKeyListener((v, keyCode, e) -> navigateCell(keyCode, e, row, column));
        }

    }
    private boolean navigateCell(int key, KeyEvent event, int row, int column) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int target = column + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1); if (target >= 0 && target < cards.get(row).size()) cards.get(row).get(target).requestFocus(); return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int target = row + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (target < 0) { if (activeTab != null) activeTab.requestFocus(); }
            else if (target < cards.size()) {
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
            else if (nextPage != null) nextPage.requestFocus(); else if (previousPage != null) previousPage.requestFocus(); return true;
        } return false;
    }
    private void updateHero(JSONObject movie, String row) {
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
        rememberPage(); detailShowing = true; searchBar.setVisibility(GONE); scroller.setVisibility(GONE); details.setVisibility(VISIBLE); details.removeAllViews();
        hero.setVisibility(GONE);
        nav.setVisibility(GONE);
        setPadding(0, 0, 0, 0);
        updateContentEdges();
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
        info.setOrientation(VERTICAL);
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
        if (firstReady && !nav.hasFocus()) play.requestFocus();
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
    void hideDetails() { boolean restore = detailShowing && !nav.hasFocus(); finishLoading(); detailShowing = false; if (detailArt != null) { content.removeView(detailArt); detailArt = null; detailPoster = null; detailBackdrop = null; } nav.setVisibility(VISIBLE); setPadding(dp(48), dp(26), dp(48), 0); updateContentEdges(); details.setVisibility(GONE); scroller.setVisibility(VISIBLE); queueArtwork(); if (restore) focusRememberedCard(); }
    void restoreFocus() { if (detailShowing) { View play = details.findViewWithTag("play"); if (play != null) play.requestFocus(); } else focusRememberedCard(); }
    private void focusRememberedCard() { if (lastCard != null && lastCard.getParent() != null) lastCard.requestFocus(); else if (!cards.isEmpty() && !cards.get(0).isEmpty()) cards.get(0).get(0).requestFocus(); }
    boolean closeSearch() { if (searchBar.getVisibility() != VISIBLE) return false; searchBar.setVisibility(GONE); searchTab.requestFocus(); return true; }
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
