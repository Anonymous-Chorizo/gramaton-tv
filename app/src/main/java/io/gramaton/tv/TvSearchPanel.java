package io.gramaton.tv;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Remote-first query entry, with the same field above the editor and the result grid. */
final class TvSearchPanel {
    private enum Mode { HIDDEN, EDITING, RESULTS }

    private static final int COLUMNS = 7;
    private static final int MAX_SUGGESTIONS = 5;
    private static final int BACKGROUND = 0xff1b2638;
    private static final int TEXT = 0xfff5f7fb;
    private static final int MUTED = 0xffaebbd0;
    private static final int DARK = 0xff090e17;
    private static final int DARK_MUTED = 0xff475569;
    private static final int ACCENT = 0xffd5e7ff;

    final LinearLayout header;
    final LinearLayout editor;
    private final EditText query;
    private final Activity activity;
    private final LinearLayout keyboard, suggestions, suggestionColumn, keypadColumn;
    private final TvPhoneSearch phone;
    private final CatalogueSuggestions catalogueSuggestions;
    private final TvImageLoader artwork;
    private final ProgressBar more;
    private final TextView hint;
    private final Button space, clear, search;
    private final InputMethodManager inputMethod;
    private final Runnable openEditor, focusMenu, focusResults;
    private final Consumer<JSONObject> openTitle;
    private final Consumer<String> submit;
    private final android.text.method.KeyListener textInput;
    private final List<Button> keys = new ArrayList<>();
    private final List<View> choices = new ArrayList<>();
    private final LinkedHashMap<String, JSONObject> knownTitles = new LinkedHashMap<>();
    private Mode mode = Mode.HIDDEN;
    private boolean numbers, foreground, editorActive, suggestionsUnavailable;
    private String suggestionTerm;
    private Button lastKey;

    TvSearchPanel(Activity activity, CatalogueSuggestions catalogueSuggestions, TvImageLoader artwork,
                  Runnable openEditor, Runnable focusMenu, Runnable focusResults,
                  Consumer<JSONObject> openTitle, Consumer<String> submit) {
        this.activity = activity;
        this.catalogueSuggestions = catalogueSuggestions;
        this.artwork = artwork;
        this.openEditor = openEditor;
        this.focusMenu = focusMenu;
        this.focusResults = focusResults;
        this.openTitle = openTitle;
        this.submit = submit;
        inputMethod = activity.getSystemService(InputMethodManager.class);

        header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, 0, dp(14));
        query = new EditText(activity);
        query.setId(View.generateViewId());
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textInput = query.getKeyListener();
        query.setTextColor(TEXT);
        query.setHintTextColor(MUTED);
        query.setHint("Search movies and TV shows");
        query.setTextSize(19);
        query.setPaddingRelative(dp(16), 0, dp(16), 0);
        query.setMinimumHeight(0);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setShowSoftInputOnFocus(false);
        query.setBackground(background(BACKGROUND, BACKGROUND, TEXT));
        header.addView(query, new LinearLayout.LayoutParams(0, dp(46), 1));
        more = new ProgressBar(activity);
        more.setContentDescription("Loading more titles");
        more.setIndeterminateTintList(ColorStateList.valueOf(0xff70b8ff));
        more.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams moreSize = new LinearLayout.LayoutParams(dp(18), dp(18));
        moreSize.leftMargin = dp(14);
        header.addView(more, moreSize);

        editor = new LinearLayout(activity);
        editor.setPadding(dp(4), dp(4), dp(32), 0);
        editor.setClipChildren(false);
        suggestionColumn = column();
        LinearLayout.LayoutParams leftSize = new LinearLayout.LayoutParams(0, -2, 4);
        leftSize.rightMargin = dp(26);
        editor.addView(suggestionColumn, leftSize);
        hint = label("", 14, MUTED);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        suggestionColumn.addView(hint);
        suggestions = column();
        suggestionColumn.addView(suggestions);

        keypadColumn = column();
        editor.addView(keypadColumn, new LinearLayout.LayoutParams(0, -2, 5));
        keyboard = column();
        keypadColumn.addView(keyboard);
        LinearLayout actions = new LinearLayout(activity);
        keypadColumn.addView(actions);
        space = control("Space", false);
        space.setOnClickListener(v -> addCharacter(" "));
        clear = control("Clear", false);
        clear.setOnClickListener(v -> query.setText(""));
        search = control("Search", true);
        search.setOnClickListener(v -> submitQuery());
        for (Button button : new Button[]{space, clear, search}) addCell(actions, button);
        space.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || key != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            return focusSuggestion(choices.size() - 1);
        });
        phone = new TvPhoneSearch(activity, term -> {
            hideInputMethod();
            query.setText(term);
            query.setSelection(query.length());
            if (search.isEnabled()) search.requestFocus(); else focusKeyboard();
        });
        placePhoneCode(true);

        query.setOnClickListener(v -> {
            if (!isEditing()) this.openEditor.run();
            else showInputMethod();
        });
        query.setOnKeyListener(this::navigateQuery);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submitQuery();
            return true;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isEditing() && editorActive) refreshSuggestions();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        buildKeys();
        updateVisibility(false);
    }

    boolean isOpen() { return mode != Mode.HIDDEN; }
    boolean isEditing() { return mode == Mode.EDITING; }

    void edit(boolean fromField) {
        mode = Mode.EDITING;
        configureQuery(true);
        updateVisibility(false);
        if (fromField) query.requestFocus(); else focusKeyboard();
    }

    void results(String term) {
        hideInputMethod();
        mode = Mode.RESULTS;
        query.setText(term);
        configureQuery(false);
        updateVisibility(false);
        focusField();
    }

    void close() {
        hideInputMethod();
        mode = Mode.HIDDEN;
        query.setText("");
        if (numbers) toggleNumbers();
        lastKey = keys.get(0);
        updateVisibility(false);
    }

    void updateVisibility(boolean detailsShowing) {
        header.setVisibility(isOpen() && !detailsShowing ? View.VISIBLE : View.GONE);
        editor.setVisibility(isEditing() && !detailsShowing ? View.VISIBLE : View.GONE);
        updateEditorActivity();
    }

    void setForeground(boolean value) {
        foreground = value;
        updateEditorActivity();
    }

    private void updateEditorActivity() {
        boolean visible = foreground && editor.getVisibility() == View.VISIBLE;
        phone.setActive(visible);
        if (editorActive == visible) return;
        editorActive = visible;
        suggestionTerm = null;
        if (visible) refreshSuggestions(); else catalogueSuggestions.cancel();
    }

    void dispose() { catalogueSuggestions.close(); phone.dispose(); }

    void setPaging(boolean loading) { more.setVisibility(loading ? View.VISIBLE : View.INVISIBLE); }
    void focusField() { query.requestFocus(); }
    void focusKeyboard() {
        if (!lastKey.isEnabled()) lastKey = keys.get(0);
        lastKey.requestFocus();
    }

    void indexTitles(JSONArray titles) {
        boolean changed = false;
        for (int i = 0; i < titles.length(); i++) {
            JSONObject item = titles.optJSONObject(i);
            if (item == null || item.optString("title").trim().isEmpty()) continue;
            Uri page = Uri.parse(item.optString("url"));
            if (MainActivity.isMovie(page) && knownTitles.putIfAbsent(page.getPath(), item) == null) changed = true;
        }
        while (knownTitles.size() > 2000) knownTitles.remove(knownTitles.keySet().iterator().next());
        if (changed && editorActive && suggestionsUnavailable && !suggestions.hasFocus()) showLocalSuggestions(suggestionTerm);
    }

    private void refreshSuggestions() {
        String queryText = query.getText().toString().trim();
        String term = queryText.toLowerCase(Locale.ROOT);
        search.setEnabled(!term.isEmpty());
        if (term.equals(suggestionTerm)) return;
        suggestionTerm = term;
        suggestionsUnavailable = false;
        placePhoneCode(term.isEmpty());
        if (term.isEmpty()) {
            catalogueSuggestions.cancel();
            showSuggestions(Collections.emptyList(), "");
            return;
        }
        showSuggestions(Collections.emptyList(), "Looking up titles…");
        catalogueSuggestions.lookup(queryText, titles -> {
            suggestionsUnavailable = titles == null;
            if (suggestionsUnavailable) showLocalSuggestions(term);
            else showSuggestions(titles, "No suggestions");
        });
    }

    private void placePhoneCode(boolean emptyQuery) {
        LinearLayout column = emptyQuery ? suggestionColumn : keypadColumn;
        if (phone.getParent() == column) return;
        if (phone.getParent() instanceof LinearLayout previous) previous.removeView(phone);
        phone.setProminent(emptyQuery);
        column.addView(phone, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showLocalSuggestions(String term) {
        List<JSONObject> titles = new ArrayList<>();
        for (JSONObject item : knownTitles.values()) {
            if (item.optString("title").trim().toLowerCase(Locale.ROOT).startsWith(term)) titles.add(item);
            if (titles.size() == MAX_SUGGESTIONS) break;
        }
        if (titles.size() < MAX_SUGGESTIONS) {
            for (JSONObject item : knownTitles.values()) {
                String name = item.optString("title").trim().toLowerCase(Locale.ROOT);
                if (!name.startsWith(term) && name.contains(term)) titles.add(item);
                if (titles.size() == MAX_SUGGESTIONS) break;
            }
        }
        showSuggestions(titles, "Couldn’t load suggestions");
    }

    private void showSuggestions(List<JSONObject> titles, String emptyMessage) {
        // Move focus before removing a suggestion that may still be selected by a remote.
        if (suggestions.hasFocus()) focusKeyboard();
        choices.clear();
        suggestions.removeAllViews();
        for (int i = 0; i < Math.min(MAX_SUGGESTIONS, titles.size()); i++) addSuggestion(titles.get(i));
        hint.setVisibility(choices.isEmpty() && !emptyMessage.isEmpty() ? View.VISIBLE : View.GONE);
        hint.setText(emptyMessage);
    }

    private void addSuggestion(JSONObject item) {
        String name = item.optString("title");
        String releaseYear = item.optString("year");
        LinearLayout choice = new LinearLayout(activity);
        choice.setGravity(Gravity.CENTER_VERTICAL);
        choice.setPaddingRelative(dp(7), 0, dp(10), 0);
        choice.setFocusableInTouchMode(true);
        choice.setBackground(background(BACKGROUND, TEXT, TEXT));
        choice.setContentDescription(releaseYear.isEmpty() ? name : name + ", " + releaseYear);

        ImageView thumbnail = new ImageView(activity);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(rounded(DARK_MUTED, 0));
        thumbnail.setClipToOutline(true);
        thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        choice.addView(thumbnail, new LinearLayout.LayoutParams(dp(24), dp(36)));
        artwork.load(item.optString("image"), thumbnail, false);

        LinearLayout copy = column();
        copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams copySize = new LinearLayout.LayoutParams(0, -2, 1);
        copySize.setMarginStart(dp(10));
        choice.addView(copy, copySize);
        TextView title = label(name, 15, TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        copy.addView(title);
        TextView year = label(releaseYear, 12, MUTED);
        year.setIncludeFontPadding(false);
        year.setVisibility(releaseYear.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams yearSize = new LinearLayout.LayoutParams(-1, -2);
        yearSize.topMargin = dp(2);
        copy.addView(year, yearSize);
        choice.setOnFocusChangeListener((view, focused) -> {
            title.setTextColor(focused ? DARK : TEXT);
            year.setTextColor(focused ? DARK_MUTED : MUTED);
        });

        int row = choices.size();
        choice.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: focusKeyboard(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: focusMenu.run(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row > 0) choices.get(row - 1).requestFocus(); else focusField();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (row + 1 < choices.size()) choices.get(row + 1).requestFocus();
                    return true;
                default: return false;
            }
        });
        choice.setOnClickListener(v -> {
            hideInputMethod();
            openTitle.accept(item);
        });
        choices.add(choice);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(46));
        size.bottomMargin = dp(4);
        suggestions.addView(choice, size);
    }

    private void buildKeys() {
        keyboard.removeAllViews();
        keys.clear();
        String characters = numbers ? "1234567890-'." : "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int count = characters.length() + 2;
        for (int i = 0; i < count; i++) {
            int index = i;
            String value = i < characters.length() ? characters.substring(i, i + 1) : i == count - 1 ? "⌫" : numbers ? "ABC" : "123";
            Button key = control(value, false);
            if (i == count - 1) key.setContentDescription("Delete");
            key.setOnFocusChangeListener((view, focused) -> { if (focused) lastKey = key; });
            key.setOnClickListener(v -> {
                if (value.equals("⌫")) deleteCharacter();
                else if (value.equals("ABC") || value.equals("123")) { toggleNumbers(); focusKeyboard(); }
                else addCharacter(value.toLowerCase(Locale.ROOT));
            });
            key.setOnKeyListener((v, code, event) -> navigateKey(index, code, event));
            keys.add(key);
        }
        for (int start = 0; start < count; start += COLUMNS) {
            LinearLayout row = new LinearLayout(activity);
            keyboard.addView(row);
            for (int column = 0; column < COLUMNS; column++) {
                int index = start + column;
                addCell(row, index < count ? keys.get(index) : new View(activity));
            }
        }
        lastKey = keys.get(0);
    }

    private boolean navigateKey(int index, int code, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int column = index % COLUMNS;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (column == 0) return focusSuggestion(index / COLUMNS);
                keys.get(index - 1).requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (column + 1 < COLUMNS && index + 1 < keys.size()) keys.get(index + 1).requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (index < COLUMNS) focusField(); else keys.get(index - COLUMNS).requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (index + COLUMNS < keys.size()) keys.get(index + COLUMNS).requestFocus();
                else if (column >= 5 && search.isEnabled()) search.requestFocus();
                else if (column < 2) space.requestFocus();
                else clear.requestFocus();
                return true;
            default: return false;
        }
    }

    private boolean navigateQuery(View view, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (isEditing()) focusKeyboard(); else focusResults.run();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT && !isEditing()) { focusMenu.run(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (!isEditing()) { openEditor.run(); return true; }
            if (key == KeyEvent.KEYCODE_ENTER) { submitQuery(); return true; }
        }
        return false;
    }

    private boolean focusSuggestion(int row) {
        if (choices.isEmpty()) focusMenu.run();
        else choices.get(Math.min(row, choices.size() - 1)).requestFocus();
        return true;
    }

    private void addCharacter(String value) {
        int start = query.getSelectionStart(), end = query.getSelectionEnd();
        if (start < 0 || end < 0) start = end = query.length();
        query.getText().replace(Math.min(start, end), Math.max(start, end), value);
    }

    private void deleteCharacter() {
        int start = query.getSelectionStart(), end = query.getSelectionEnd();
        if (start < 0 || end < 0) start = end = query.length();
        if (start != end) query.getText().delete(Math.min(start, end), Math.max(start, end));
        else if (start > 0) query.getText().delete(Character.offsetByCodePoints(query.getText(), start, -1), start);
    }

    private void toggleNumbers() { numbers = !numbers; buildKeys(); }

    private void configureQuery(boolean editable) {
        query.setKeyListener(editable ? textInput : null);
        query.setFocusableInTouchMode(true);
        query.setClickable(true);
        query.setCursorVisible(editable);
        if (editable) query.setSelection(query.length());
    }

    private void showInputMethod() {
        if (inputMethod == null) return;
        query.setShowSoftInputOnFocus(true);
        inputMethod.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
        query.setShowSoftInputOnFocus(false);
    }

    private void hideInputMethod() {
        if (inputMethod != null && query.getWindowToken() != null) inputMethod.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void submitQuery() {
        String term = query.getText().toString().trim();
        if (!term.isEmpty()) submit.accept(term);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button control(String label, boolean accent) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        button.setFocusableInTouchMode(true);
        button.setBackground(background(accent ? ACCENT : BACKGROUND, TEXT, TEXT));
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},
            {android.R.attr.state_focused}, {}}, new int[]{MUTED, DARK, accent ? DARK : TEXT}));
        return button;
    }

    private StateListDrawable background(int normal, int focused, int outline) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, rounded(BACKGROUND, 0));
        states.addState(new int[]{android.R.attr.state_focused}, rounded(focused, outline));
        states.addState(new int[]{}, rounded(normal, 0));
        return states;
    }

    private GradientDrawable rounded(int color, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(7));
        if (border != 0) drawable.setStroke(dp(2), border);
        return drawable;
    }

    private void addCell(LinearLayout row, View view) {
        boolean compact = activity.getResources().getConfiguration().screenHeightDp < 480;
        int height = compact ? 27 : 39;
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(height), 1);
        if (row.getChildCount() > 0) size.leftMargin = dp(6);
        size.bottomMargin = dp(compact ? 4 : 6);
        row.addView(view, size);
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
