package io.gramaton.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, shared artwork cache. Callers request only visible/nearby artwork. */
final class TvImageLoader {
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<WeakReference<ImageView>>> waiting = new HashMap<>();
    private boolean closed;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private final Map<ImageView, Runnable> fallbacks = new WeakHashMap<>();

    void load(String url, ImageView view, boolean hero) {
        load(url, view, hero, null);
    }
    void load(String url, ImageView view, boolean hero, Runnable fallback) {
        if (fallback != null) fallbacks.put(view, fallback);
        String key = url + (hero ? "#hero" : "#poster");
        if (closed) return;
        if (!url.startsWith("https://")) {
            Runnable failure = fallbacks.remove(view);
            if (failure != null) failure.run();
            return;
        }
        if (!key.equals(view.getTag())) { view.setImageDrawable(null); view.setTag(key); }
        Bitmap hit = cache.get(key);
        if (hit != null) { fallbacks.remove(view); view.setImageBitmap(hit); return; }
        List<WeakReference<ImageView>> targets = waiting.get(key);
        if (targets != null) {
            for (WeakReference<ImageView> target : targets) if (target.get() == view) return;
            targets.add(new WeakReference<>(view)); return;
        }
        targets = new ArrayList<>(); targets.add(new WeakReference<>(view)); waiting.put(key, targets);
        workers.submit(() -> {
            Bitmap result = null; HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
                byte[] bytes;
                try (java.io.InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (out.size() + read > 6 * 1024 * 1024) throw new java.io.IOException("Artwork too large");
                        out.write(buffer, 0, read);
                    }
                    bytes = out.toByteArray();
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                int targetWidth = hero ? 1000 : 360;
                while (bounds.outWidth / (options.inSampleSize * 2) >= targetWidth) options.inSampleSize *= 2;
                result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Exception ignored) { /* A readable title remains when artwork is unavailable. */ }
            finally { if (connection != null) connection.disconnect(); }
            Bitmap bitmap = result;
            main.post(() -> {
                List<WeakReference<ImageView>> views = waiting.remove(key);
                if (closed) return;
                if (bitmap != null) cache.put(key, bitmap);
                if (views != null) for (WeakReference<ImageView> weak : views) {
                    ImageView target = weak.get();
                    if (target == null || !key.equals(target.getTag())) continue;
                    Runnable failure = fallbacks.remove(target);
                    if (bitmap != null) target.setImageBitmap(bitmap);
                    else if (failure != null) failure.run();
                }
            });
        });
    }
    void trimCache() { cache.evictAll(); }
    void close() { closed = true; workers.shutdownNow(); main.removeCallbacksAndMessages(null); waiting.clear(); fallbacks.clear(); cache.evictAll(); }
}
