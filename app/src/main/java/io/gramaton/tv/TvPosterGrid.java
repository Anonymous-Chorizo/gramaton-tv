package io.gramaton.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recycles poster views while the viewer browses multiple pages of one listing. */
final class TvPosterGrid extends RecyclerView {
    interface Actions {
        void open(JSONObject movie);
        void focusMenu();
        void focusFilters();
        void reachEnd();
        void nearEnd();
    }

    private final GridLayoutManager layout;
    private final PosterAdapter posters;
    private final TvImageLoader artwork;
    private final Actions actions;
    private final Runnable checkEnd = this::checkNearEnd;
    private String selectedUrl = "";
    private int posterHeight;

    TvPosterGrid(Context context, TvImageLoader artwork, Actions actions) {
        super(context);
        this.artwork = artwork;
        this.actions = actions;
        layout = new GridLayoutManager(context, 1);
        posters = new PosterAdapter();
        setLayoutManager(layout);
        setAdapter(posters);
        setItemAnimator(null);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setPadding(0, 0, dp(32), dp(12));
        setClipToPadding(false);
        setClipChildren(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
        configureColumns(context.getResources().getDisplayMetrics().widthPixels - dp(TvNavigationDrawer.RAIL_WIDTH + 20));
        addItemDecoration(new ItemDecoration() {
            @Override public void getItemOffsets(Rect out, View view, RecyclerView parent, State state) {
                int index = parent.getChildAdapterPosition(view);
                if (index == NO_POSITION) return;
                int columns = layout.getSpanCount();
                int column = index % columns;
                int gap = dp(13);
                out.set(gap * column / columns, dp(8), gap - gap * (column + 1) / columns, dp(9));
            }
        });
        addOnScrollListener(new OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { checkPagingSoon(); }
        });
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0 && width != oldWidth) post(() -> configureColumns(getWidth()));
    }

    private void configureColumns(int viewport) {
        int width = Math.max(dp(24), viewport - getPaddingLeft() - getPaddingRight());
        int gap = dp(13);
        int columns = Math.max(1, (width + gap) / (dp(124) + gap));
        int nextHeight = Math.round(((width - (columns - 1) * gap) / columns - dp(8)) * 1.5f) + dp(8);
        if (columns == layout.getSpanCount() && nextHeight == posterHeight) return;
        layout.setSpanCount(columns);
        posterHeight = nextHeight;
        invalidateItemDecorations();
        posters.notifyDataSetChanged();
    }

    void replace(JSONArray items) {
        selectedUrl = "";
        posters.items.clear();
        posters.positions.clear();
        posters.add(items);
        posters.notifyDataSetChanged();
    }

    int append(JSONArray items) {
        int start = posters.getItemCount();
        posters.add(items);
        int added = posters.getItemCount() - start;
        if (added > 0) posters.notifyItemRangeInserted(start, added);
        return added;
    }

    boolean hasPosters() { return posters.getItemCount() > 0; }
    JSONArray copyItems() { return new JSONArray(posters.items); }
    String selectedUrl() { return selectedUrl; }
    int firstVisiblePosition() { return layout.findFirstVisibleItemPosition(); }
    int firstVisibleOffset() {
        View first = layout.findViewByPosition(firstVisiblePosition());
        return first == null ? 0 : layout.getDecoratedTop(first) - getPaddingTop();
    }

    void restore(String url, int position, int offset) {
        selectedUrl = posters.positions.containsKey(url) ? url : "";
        layout.scrollToPositionWithOffset(position >= 0 && position < posters.getItemCount() ? position : 0, offset);
    }

    boolean focusSelected() {
        if (!isShown() || !hasPosters()) return false;
        int position = posters.positions.getOrDefault(selectedUrl, 0);
        View tile = layout.findViewByPosition(position);
        if (tile != null && !hasPendingAdapterUpdates()) return tile.requestFocus();
        layout.scrollToPosition(position);
        final String url = posters.items.get(position).optString("url");
        getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                View visible = layout.findViewByPosition(position);
                if (isShown() && visible != null && position < posters.getItemCount() &&
                        url.equals(posters.items.get(position).optString("url"))) visible.requestFocus();
                return true;
            }
        });
        return true;
    }

    void checkPagingSoon() {
        removeCallbacks(checkEnd);
        postOnAnimation(checkEnd);
    }

    private void checkNearEnd() {
        int last = layout.findLastVisibleItemPosition();
        if (isShown() && last != NO_POSITION && last >= posters.getItemCount() - layout.getSpanCount() * 2) actions.nearEnd();
    }

    private boolean navigate(int position, int key, KeyEvent event) {
        if (position == NO_POSITION || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int columns = layout.getSpanCount();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT && position % columns == 0) {
            actions.focusMenu();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP && position < columns) {
            actions.focusFilters();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT && (position % columns == columns - 1 || position == posters.getItemCount() - 1)) return true;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && position / columns == (posters.getItemCount() - 1) / columns) {
            actions.reachEnd();
            return true;
        }
        return false;
    }

    private final class PosterAdapter extends Adapter<PosterHolder> {
        final List<JSONObject> items = new ArrayList<>();
        final Map<String, Integer> positions = new HashMap<>();

        void add(JSONArray data) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject movie = data.optJSONObject(i);
                if (movie == null) continue;
                String url = movie.optString("url");
                if (!url.isEmpty() && !positions.containsKey(url)) {
                    positions.put(url, items.size());
                    items.add(movie);
                }
            }
        }

        @Override public int getItemCount() { return items.size(); }
        @Override public PosterHolder onCreateViewHolder(ViewGroup parent, int type) {
            return new PosterHolder(new FrameLayout(parent.getContext()));
        }

        @Override public void onBindViewHolder(PosterHolder holder, int position) {
            JSONObject movie = items.get(position);
            holder.itemView.getLayoutParams().height = posterHeight;
            holder.itemView.setContentDescription(movie.optString("title") + ". " + movie.optString("meta"));
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
            artwork.load(movie.optString("image"), holder.image, false);
        }

        @Override public void onViewRecycled(PosterHolder holder) {
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
        }
    }

    private final class PosterHolder extends ViewHolder {
        final ImageView image;

        PosterHolder(FrameLayout tile) {
            super(tile);
            tile.setLayoutParams(new RecyclerView.LayoutParams(-1, posterHeight));
            tile.setFocusableInTouchMode(true);
            tile.setDefaultFocusHighlightEnabled(false);
            tile.setPivotX(0);
            image = new ImageView(tile.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(TvColors.SURFACE, 7));
            image.setClipToOutline(true);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
            int ring = dp(2);
            LayerDrawable outline = new LayerDrawable(new Drawable[]{round(Color.WHITE, 11), round(Color.BLACK, 9)});
            outline.setLayerInset(1, ring, ring, ring, ring);
            tile.setPadding(2 * ring, 2 * ring, 2 * ring, 2 * ring);
            tile.setOnFocusChangeListener((view, focused) -> {
                view.setBackground(focused ? outline : null);
                view.setPadding(2 * ring, 2 * ring, 2 * ring, 2 * ring);
                view.animate().translationX(focused ? 2 * ring : 0).scaleX(focused ? 1.055f : 1)
                    .scaleY(focused ? 1.055f : 1).translationZ(focused ? dp(4) : 0).setDuration(140).start();
                int position = getBindingAdapterPosition();
                if (focused && position != NO_POSITION) {
                    selectedUrl = posters.items.get(position).optString("url");
                    checkPagingSoon();
                }
            });
            tile.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != NO_POSITION) actions.open(posters.items.get(position));
            });
            tile.setOnKeyListener((view, key, event) -> navigate(getBindingAdapterPosition(), key, event));
        }
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
