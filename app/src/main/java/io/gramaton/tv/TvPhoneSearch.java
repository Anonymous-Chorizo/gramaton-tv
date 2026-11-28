package io.gramaton.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Shows a scan-to-type link while the search editor is on screen. */
final class TvPhoneSearch extends LinearLayout {
    private final ImageView code;
    private final boolean compactScreen;
    private final Consumer<String> receiveQuery;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PhoneSearchSession session;
    private boolean active;
    private int generation;

    TvPhoneSearch(Activity activity, Consumer<String> receiveQuery) {
        super(activity);
        this.receiveQuery = receiveQuery;
        compactScreen = getResources().getConfiguration().screenHeightDp < 480;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        code = new ImageView(activity);
        code.setContentDescription("QR code for typing from your phone");
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.WHITE);
        surface.setCornerRadius(dp(13));
        code.setBackground(surface);
        code.setClipToOutline(true);
        addView(code);
        TextView title = new TextView(activity);
        title.setText("Type from your phone");
        title.setTextColor(TvColors.WHITE);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        LayoutParams titleSize = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleSize.topMargin = dp(compactScreen ? 5 : 8);
        addView(title, titleSize);
        TextView hint = new TextView(activity);
        hint.setText("Scan on the same Wi-Fi network");
        hint.setTextColor(TvColors.MUTED);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    void setProminent(boolean prominent) {
        int size = dp(prominent ? 148 : compactScreen ? 66 : 108);
        code.setLayoutParams(new LayoutParams(size, size));
        setPadding(0, dp(prominent ? 0 : compactScreen ? 7 : 13), 0, 0);
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        int attempt = ++generation;
        if (!active) {
            if (session != null) session.close();
            session = null;
            code.setImageDrawable(null);
            setVisibility(GONE);
            return;
        }
        worker.execute(() -> runSession(attempt));
    }

    private void runSession(int attempt) {
        Inet4Address address = findAddress();
        if (address == null) return;
        try (PhoneSearchSession pending = new PhoneSearchSession(address, term -> main.post(() -> {
            if (attempt == generation && active) receiveQuery.accept(term);
        }))) {
            Bitmap qr = qrCode(pending.url());
            main.post(() -> {
                if (!active || attempt != generation) { pending.close(); return; }
                session = pending;
                code.setImageBitmap(qr);
                setVisibility(VISIBLE);
            });
            pending.serve();
        } catch (IOException | WriterException ignored) { /* Phone typing is optional when local networking is unavailable. */ }
    }

    private Inet4Address findAddress() {
        ConnectivityManager network = getContext().getSystemService(ConnectivityManager.class);
        if (network == null) return null;
        LinkProperties link = network.getLinkProperties(network.getActiveNetwork());
        if (link == null) return null;
        for (LinkAddress address : link.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) return ipv4;
        }
        return null;
    }

    private static Bitmap qrCode(String url) throws WriterException {
        int size = 256;
        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    void dispose() {
        setActive(false);
        worker.shutdownNow();
    }
}
