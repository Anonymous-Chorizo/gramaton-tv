package io.gramaton.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdates {
    private static final String REPO = "/Anonymous-Chorizo/gramaton-tv";
    private static final String RELEASES = "https://api.github.com/repos" + REPO + "/releases/latest";
    private static final long MAX_APK_BYTES = 128L * 1024 * 1024;
    private static final int MAX_RELEASE_BYTES = 1024 * 1024;
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private HttpURLConnection activeConnection;
    private boolean closed;

    AppUpdates(Activity activity) {
        this.activity = activity;
    }

    void check() {
        if (closed || dialog != null) return;
        show("Checking for updates…", false);
        worker.execute(() -> {
            try {
                PackageInfo installed = installedPackage();
                Release release = latestRelease(activity.getString(R.string.update_asset_name), installed.versionName);
                activity.runOnUiThread(() -> {
                    if (!replaceDialog()) return;
                    if (release == null) {
                        show("You’re on the latest available version (" + installed.versionName + ").", true);
                        return;
                    }
                    display(new AlertDialog.Builder(activity).setTitle("Gramaton " + release.tag + " is available")
                        .setMessage("Download the update and confirm installation on your TV. Your Watchlist and resume history stay on this device.")
                        .setPositiveButton("Download", (d, w) -> download(release))
                        .setNegativeButton("Later", null));
                });
            } catch (NoReleaseException e) {
                reportFailure(e.getMessage());
            } catch (Exception e) {
                reportFailure("Couldn’t check GitHub for updates. Try again later.");
            }
        });
    }

    private Release latestRelease(String assetName, String installedVersion) throws Exception {
        HttpURLConnection connection = open(new URL(RELEASES), false);
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new NoReleaseException("No update has been published on GitHub yet.");
            requireSuccess(status);
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    if (json.size() + count > MAX_RELEASE_BYTES) throw new IOException("Release response too large");
                    json.write(buffer, 0, count);
                }
            }
            JSONObject release = new JSONObject(json.toString(StandardCharsets.UTF_8.name()));
            String tag = release.getString("tag_name");
            if (compareVersions(tag, installedVersion) <= 0) return null;
            JSONArray assets = release.getJSONArray("assets");
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.getJSONObject(index);
                if (!assetName.equals(asset.optString("name"))) continue;
                URL url = new URL(asset.getString("browser_download_url"));
                String digest = asset.optString("digest");
                long size = asset.optLong("size");
                if (!trusted(url, "github.com") || !url.getPath().equals(REPO + "/releases/download/" + tag + "/" + assetName)
                        || !digest.matches("sha256:[0-9a-f]{64}") || size <= 0 || size > MAX_APK_BYTES) {
                    throw new IOException("Invalid APK release asset");
                }
                return new Release(tag, url, digest.substring(7), size);
            }
            throw new NoReleaseException("The latest release doesn’t include an APK for this TV.");
        } finally {
            connection.disconnect();
        }
    }

    static int compareVersions(String left, String right) throws IOException {
        String[] first = versionParts(left);
        String[] second = versionParts(right);
        for (int i = 0; i < 3; i++) {
            int a = i < first.length ? Integer.parseInt(first[i]) : 0;
            int b = i < second.length ? Integer.parseInt(second[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static String[] versionParts(String value) throws IOException {
        if (value == null || !value.matches("v?[0-9]{1,8}(\\.[0-9]{1,8}){1,2}")) {
            throw new IOException("Unsupported release version");
        }
        return (value.startsWith("v") ? value.substring(1) : value).split("\\.");
    }

    private void download(Release release) {
        dialog = null;
        show("Downloading Gramaton " + release.tag + "…", false);
        worker.execute(() -> {
            File apk = null;
            try {
                apk = downloadApk(release);
                verifyApk(apk, release.tag);
                File verified = apk;
                activity.runOnUiThread(() -> {
                    if (replaceDialog()) install(verified);
                    else verified.delete();
                });
            } catch (Exception e) {
                if (apk != null) apk.delete();
                reportFailure("Couldn’t download or verify the update. Your installed app and data are safe.");
            }
        });
    }

    private File downloadApk(Release release) throws Exception {
        File directory = new File(activity.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Update directory unavailable");
        File[] previous = directory.listFiles();
        if (previous != null) for (File file : previous) file.delete();
        File apk = File.createTempFile("gramaton-", ".apk", directory);
        boolean complete = false;
        HttpURLConnection connection = null;
        try {
            connection = open(release.url, true);
            requireSuccess(connection.getResponseCode());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    bytes += count;
                    if (bytes > release.size) throw new IOException("Unexpected APK size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (bytes != release.size || !release.digest.equals(hex(digest.digest()))) {
                throw new IOException("APK failed checksum verification");
            }
            complete = true;
            return apk;
        } finally {
            if (connection != null) connection.disconnect();
            if (!complete) apk.delete();
        }
    }

    private synchronized void trackConnection(HttpURLConnection connection) throws IOException {
        if (closed) {
            connection.disconnect();
            throw new IOException("Update check closed");
        }
        activeConnection = connection;
    }

    private HttpURLConnection open(URL url, boolean asset) throws IOException {
        for (int redirect = 0; redirect < 5; redirect++) {
            boolean allowed = asset
                ? trusted(url, "github.com") || trusted(url, "release-assets.githubusercontent.com")
                    || trusted(url, "objects.githubusercontent.com")
                : trusted(url, "api.github.com");
            if (!allowed) throw new IOException("Unexpected update host");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Gramaton-TV");
            if (!asset) connection.setRequestProperty("Accept", "application/vnd.github+json");
            trackConnection(connection);
            boolean connected = false;
            try {
                int status = connection.getResponseCode();
                if (status < 300 || status >= 400 || status == 304) {
                    connected = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirect location missing");
                url = new URL(url, location);
            } finally {
                if (!connected) connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static boolean trusted(URL url, String host) {
        return "https".equals(url.getProtocol()) && host.equals(url.getHost()) && url.getUserInfo() == null
            && (url.getPort() == -1 || url.getPort() == 443);
    }

    private static void requireSuccess(int status) throws IOException {
        if (status != 200) throw new IOException("Update server returned " + status);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 0xff) >>> 4, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    @SuppressWarnings("deprecation")
    private PackageInfo installedPackage() throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), flags);
    }

    @SuppressWarnings("deprecation")
    private void verifyApk(File apk, String releaseTag) throws Exception {
        PackageInfo installed = installedPackage();
        boolean supportsRotation = Build.VERSION.SDK_INT >= 28;
        int flags = supportsRotation ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo download = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (download == null || !installed.packageName.equals(download.packageName)
                || compareVersions(download.versionName, releaseTag) != 0) {
            throw new IOException("APK does not match this release");
        }
        if (supportsRotation) {
            if (download.getLongVersionCode() <= installed.getLongVersionCode()
                    || download.signingInfo == null || installed.signingInfo == null) {
                throw new IOException("APK is not a signed upgrade");
            }
            Signature[] signers = installed.signingInfo.getApkContentsSigners();
            Signature[] candidate = download.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0 || candidate == null || candidate.length == 0) {
                throw new IOException("APK signer is missing");
            }
            if (Arrays.equals(signers, candidate)) return;
            Signature[] history = download.signingInfo.getSigningCertificateHistory();
            if (signers.length == 1 && history != null && Arrays.asList(history).contains(signers[0])) return;
        } else if (download.versionCode > installed.versionCode && installed.signatures != null
                && installed.signatures.length > 0 && Arrays.equals(installed.signatures, download.signatures)) {
            return;
        }
        throw new IOException("APK signer does not match this installation");
    }

    private void install(File apk) {
        Uri link = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".updates")
            .appendPath(apk.getName()).build();
        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(link, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(install);
        } catch (ActivityNotFoundException | SecurityException e) {
            show("This TV couldn’t open the APK installer.", true);
        }
    }

    private void reportFailure(String message) {
        activity.runOnUiThread(() -> {
            if (replaceDialog()) show(message, true);
        });
    }

    private boolean replaceDialog() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return false;
        if (dialog != null) dialog.dismiss();
        return true;
    }

    private void show(String message, boolean dismissible) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("App updates")
            .setMessage(message).setCancelable(dismissible);
        if (dismissible) builder.setPositiveButton("OK", null);
        display(builder);
    }

    private void display(AlertDialog.Builder builder) {
        AlertDialog current = builder.create();
        dialog = current;
        current.setOnDismissListener(d -> { if (dialog == current) dialog = null; });
        current.show();
    }

    synchronized void close() {
        closed = true;
        if (activeConnection != null) activeConnection.disconnect();
        worker.shutdownNow();
        if (dialog != null) dialog.dismiss();
    }

    private static final class NoReleaseException extends IOException {
        NoReleaseException(String message) { super(message); }
    }

    private static final class Release {
        final String tag;
        final URL url;
        final String digest;
        final long size;

        Release(String tag, URL url, String digest, long size) {
            this.tag = tag;
            this.url = url;
            this.digest = digest;
            this.size = size;
        }
    }
}
