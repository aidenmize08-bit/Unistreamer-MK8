package com.example.streamer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.streamer.hls.HlsDownloader;
import com.example.streamer.media.Mp4Remuxer;
import com.example.streamer.net.RequestContext;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reconstructed from the supplied APK. This version keeps request context per media URL and uses
 * a dedicated HLS parser/downloader instead of the original global-header heuristic.
 */
public final class MainActivity extends Activity {
    private static final int STORAGE_REQUEST = 71;
    private static final String HOME = "https://www.google.com/";
    private static final int MAX_CANDIDATES = 120;
    private static final Set<String> BLOCKED_HOST_PARTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "doubleclick.net", "googleadservices.com", "adservice.google.com",
            "exoclick.com", "exosrv.com", "popads.net", "popcash.net")));

    private final ConcurrentHashMap<String, MediaCandidate> candidates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RequestContext> requestContexts = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private FrameLayout root;
    private WebView webView;
    private EditText urlBox;
    private TextView status;
    private ProgressBar progress;
    private Button shieldButton;
    private boolean shieldEnabled = true;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private volatile String currentPageUrl = HOME;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        buildUi();
        configureWebView();
        handleIntent(getIntent());
        if (webView.getUrl() == null) webView.loadUrl(HOME);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(new AeroBackground());
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), dp(8), dp(10), dp(10));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("☁  StreamBrowser");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), dp(7), dp(12), dp(7));
        title.setShadowLayer(3, 0, 2, 0x77004B68);
        title.setBackground(glass(0xCC48BFE3, 0xCC087EA4, 18));
        column.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(0, dp(7), 0, dp(5));
        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setTextSize(14);
        urlBox.setHint("Paste or browse to a media page");
        urlBox.setPadding(dp(12), 0, dp(12), 0);
        urlBox.setBackground(glass(0xF7FFFFFF, 0xDDE7FAFF, 18));
        urlBox.setOnEditorActionListener((v, action, event) -> {
            if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getAction() == KeyEvent.ACTION_DOWN) {
                loadAddress();
                return true;
            }
            return false;
        });
        addressRow.addView(urlBox, new LinearLayout.LayoutParams(0, dp(45), 1));
        Button go = button("GO", 0xFF72D84B, 0xFF168F4D);
        go.setOnClickListener(v -> loadAddress());
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(64), dp(45));
        goParams.setMargins(dp(7), 0, 0, 0);
        addressRow.addView(go, goParams);
        column.addView(addressRow, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(navButton("‹", v -> { if (webView.canGoBack()) webView.goBack(); }));
        controls.addView(navButton("›", v -> { if (webView.canGoForward()) webView.goForward(); }));
        controls.addView(navButton("⌂", v -> webView.loadUrl(HOME)));
        shieldButton = navButton("Shield ✓", v -> toggleShield());
        controls.addView(shieldButton);
        Button download = button("DOWNLOAD MP4", 0xFFFFD659, 0xFFF28B18);
        download.setTextColor(0xFF174A5E);
        download.setOnClickListener(v -> collectPageMediaAndShowPicker());
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(0, dp(43), 1.7f);
        downloadParams.setMargins(dp(5), 0, 0, 0);
        controls.addView(download, downloadParams);
        column.addView(controls, new LinearLayout.LayoutParams(-1, dp(49)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        column.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));

        status = new TextView(this);
        status.setText("Browse to a video and press play so its playlist can be detected.");
        status.setTextColor(0xFF174A5E);
        status.setTextSize(12);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        column.addView(status, new LinearLayout.LayoutParams(-1, dp(28)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        GradientDrawable webFrame = glass(0xFFFFFFFF, 0xFFF2FBFF, 14);
        webView.setBackground(webFrame);
        column.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new BrowserClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (!busy.get()) status.setText("Page " + newProgress + "% • " + candidates.size() + " media URL(s)");
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                webView.setVisibility(View.GONE);
            }

            @Override public void onHideCustomView() { hideCustomView(); }
        });
        webView.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            MediaCandidate.Kind kind = kindFor(url, mime);
            if (kind != null) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", userAgent == null ? settings.getUserAgentString() : userAgent);
                remember(url, kind, new RequestContext(headers, webView.getUrl(), cookieFor(url), url));
                collectPageMediaAndShowPicker();
            } else {
                toast("That download is not a recognized media format.");
            }
        });
    }

    private final class BrowserClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String url = uri.toString();
            if (shieldEnabled && blocked(uri.getHost())) {
                return new WebResourceResponse("text/plain", "utf-8", null);
            }
            MediaCandidate.Kind kind = kindFor(url, header(request.getRequestHeaders(), "Accept"));
            if (kind != null) {
                remember(url, kind, new RequestContext(request.getRequestHeaders(), currentPageUrl, cookieFor(url), url));
            }
            return null;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            currentPageUrl = url;
            urlBox.setText(url);
        }

        @Override public void onPageFinished(WebView view, String url) {
            currentPageUrl = url;
            urlBox.setText(url);
            collectPerformanceEntries(false);
        }
    }

    private void collectPageMediaAndShowPicker() {
        collectPerformanceEntries(true);
    }

    private void collectPerformanceEntries(boolean showAfter) {
        String script = "(function(){var s={},o=[];function a(u){if(!u||!/^https?:/i.test(u)||s[u])return;"
                + "s[u]=1;o.push(u);}document.querySelectorAll('video,audio,source').forEach(function(e){"
                + "a(e.currentSrc);a(e.src);});try{performance.getEntriesByType('resource').forEach(function(e){"
                + "if(/m3u8|\\.mp4|\\.webm|\\.mpd|manifest|playlist/i.test(e.name))a(e.name);});}catch(x){}"
                + "return JSON.stringify(o);})()";
        webView.evaluateJavascript(script, value -> {
            try {
                Object decoded = new JSONTokener(value).nextValue();
                JSONArray array = new JSONArray(decoded instanceof String ? (String) decoded : "[]");
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i, null);
                    MediaCandidate.Kind kind = kindFor(url, null);
                    if (url != null && kind != null) {
                        Map<String, String> headers = new LinkedHashMap<>();
                        headers.put("User-Agent", webView.getSettings().getUserAgentString());
                        remember(url, kind, new RequestContext(headers, webView.getUrl(), cookieFor(url), url));
                    }
                }
            } catch (Exception ignored) { }
            if (showAfter) showPicker();
        });
    }

    private void showPicker() {
        if (busy.get()) { toast("A download is already running."); return; }
        List<MediaCandidate> list = new ArrayList<>(candidates.values());
        Collections.sort(list, (left, right) -> Long.compare(right.observedAt, left.observedAt));
        if (list.isEmpty()) {
            toast("No media detected yet. Start playback, then try again.");
            return;
        }
        String[] labels = new String[Math.min(20, list.size())];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label();
        List<MediaCandidate> visible = list.subList(0, labels.length);
        new AlertDialog.Builder(this)
                .setTitle("Download which stream?")
                .setItems(labels, (dialog, index) -> startDownload(visible.get(index)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDownload(MediaCandidate candidate) {
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            toast("Allow storage, then tap Download again.");
            return;
        }
        if (!busy.compareAndSet(false, true)) { toast("A download is already running."); return; }
        progress.setProgress(1);
        progress.setVisibility(View.VISIBLE);
        status.setText("Preparing download…");
        String pageTitle = StorageWriter.safeName(webView.getTitle());
        worker.execute(() -> {
            File job = new File(getCacheDir(), "download-" + System.currentTimeMillis());
            File mp4 = new File(job, "output.mp4");
            try {
                if (!job.mkdirs()) throw new IOException("Could not create temporary download folder");
                if (candidate.kind == MediaCandidate.Kind.HLS) {
                    MediaCandidate chosen = chooseHlsMaster(candidate);
                    HlsDownloader downloader = new HlsDownloader(chosen.context, Locale.getDefault(),
                            (phase, percent) -> updateProgress(phase, percent), requestContexts::get);
                    HlsDownloader.Result fetched = downloader.download(chosen.url, job);
                    updateProgress("Converting to MP4", 90);
                    Mp4Remuxer.remux(fetched.video, fetched.audio, mp4);
                } else {
                    mp4 = DirectDownloader.downloadAndConvert(candidate, job,
                            percent -> updateProgress("Downloading", percent));
                }
                updateProgress("Saving", 98);
                String saved = StorageWriter.saveMp4(this, mp4, pageTitle);
                runOnUiThread(() -> {
                    status.setText("Saved: " + saved);
                    progress.setProgress(100);
                    toast("Saved to " + saved);
                });
            } catch (HlsDownloader.ProtectedMediaException protectedMedia) {
                fail("This stream uses protected or unsupported encryption. DRM is not bypassed.");
            } catch (Exception error) {
                fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                deleteTree(job);
                busy.set(false);
                runOnUiThread(() -> progress.postDelayed(() -> progress.setVisibility(View.GONE), 1200));
            }
        });
    }

    private void updateProgress(String phase, int percent) {
        runOnUiThread(() -> {
            progress.setProgress(percent);
            status.setText(phase + " • " + percent + "%");
        });
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            status.setText("Download failed: " + message);
            toast("Download failed: " + message);
        });
    }

    private void remember(String url, MediaCandidate.Kind kind, RequestContext context) {
        if (url == null || candidates.size() > MAX_CANDIDATES) trimCandidates();
        if (url != null) {
            requestContexts.put(url, context);
            if (requestContexts.size() > 400) {
                java.util.Iterator<String> keys = requestContexts.keySet().iterator();
                for (int i = 0; i < 80 && keys.hasNext(); i++) requestContexts.remove(keys.next());
            }
            if (kind == MediaCandidate.Kind.HLS) {
                for (MediaCandidate existing : candidates.values()) {
                    if (existing.kind == MediaCandidate.Kind.MPEG_TS) candidates.remove(existing.url);
                }
            } else if (kind == MediaCandidate.Kind.MPEG_TS) {
                int transportStreams = 0;
                for (MediaCandidate existing : candidates.values()) {
                    if (existing.kind == MediaCandidate.Kind.HLS) return;
                    if (existing.kind == MediaCandidate.Kind.MPEG_TS) transportStreams++;
                }
                if (transportStreams >= 3) return;
            }
            candidates.put(url, new MediaCandidate(url, kind, context));
        }
        runOnUiThread(() -> {
            if (!busy.get()) status.setText(candidates.size() + " media URL(s) detected");
        });
    }

    private MediaCandidate chooseHlsMaster(MediaCandidate selected) {
        List<MediaCandidate> playlists = new ArrayList<>();
        for (MediaCandidate item : candidates.values()) {
            if (item.kind == MediaCandidate.Kind.HLS) playlists.add(item);
        }
        Collections.sort(playlists, (left, right) -> Long.compare(left.observedAt, right.observedAt));
        for (MediaCandidate item : playlists) {
            try {
                HlsDownloader probe = new HlsDownloader(item.context, Locale.getDefault(), null,
                        requestContexts::get);
                if (probe.isMasterPlaylist(item.url)) return item;
            } catch (IOException ignored) { }
        }
        return selected;
    }

    private void trimCandidates() {
        List<MediaCandidate> list = new ArrayList<>(candidates.values());
        Collections.sort(list, (left, right) -> Long.compare(left.observedAt, right.observedAt));
        for (int i = 0; i < Math.max(1, list.size() - MAX_CANDIDATES + 20); i++) {
            candidates.remove(list.get(i).url);
        }
    }

    private static MediaCandidate.Kind kindFor(String url, String mime) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (lower.contains("m3u8") || type.contains("mpegurl")
                || ((lower.contains("manifest") || lower.contains("playlist")) && !lower.contains(".mpd"))) {
            return MediaCandidate.Kind.HLS;
        }
        if (lower.contains(".mpd") || type.contains("dash+xml")) return MediaCandidate.Kind.DASH;
        if (lower.matches(".*\\.ts(?:[?#].*)?$") || type.contains("mp2t")) return MediaCandidate.Kind.MPEG_TS;
        if (lower.matches(".*\\.mp4(?:[?#].*)?$") || type.contains("video/mp4")) return MediaCandidate.Kind.MP4;
        if (lower.matches(".*\\.webm(?:[?#].*)?$") || type.contains("webm")) return MediaCandidate.Kind.WEBM;
        return null;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static boolean blocked(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        for (String part : BLOCKED_HOST_PARTS) if (lower.equals(part) || lower.endsWith("." + part)) return true;
        return false;
    }

    private void toggleShield() {
        shieldEnabled = !shieldEnabled;
        shieldButton.setText(shieldEnabled ? "Shield ✓" : "Shield ○");
        toast("Pop-up shield " + (shieldEnabled ? "on" : "off"));
    }

    private void loadAddress() {
        String value = urlBox.getText().toString().trim();
        if (value.isEmpty()) return;
        if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) value = "https://" + value;
        webView.loadUrl(value);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) webView.loadUrl(text);
    }

    private String cookieFor(String url) {
        try { return CookieManager.getInstance().getCookie(url); }
        catch (RuntimeException ignored) { return null; }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    @Override public void onBackPressed() {
        if (customView != null) hideCustomView();
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private Button navButton(String label, View.OnClickListener listener) {
        Button button = button(label, 0xFFE7FBFF, 0xFF70CDE7);
        button.setTextColor(0xFF155B78);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(43), 1);
        params.setMargins(0, 0, dp(5), 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button button(String label, int top, int bottom) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(glass(top, bottom, 16));
        button.setElevation(dp(2));
        return button;
    }

    private GradientDrawable glass(int top, int bottom, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFFFFF, top, bottom});
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), 0xAAFFFFFF);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private final class AeroBackground extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            paint.setShader(new LinearGradient(0, 0, 0, bounds.height(),
                    new int[]{0xFF48BFE3, 0xFFC9F5FF, 0xFFF5FFFF, 0xFF7DDB62},
                    new float[]{0, .34f, .62f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, paint);
            paint.setShader(null);
            paint.setColor(0x55FFFFFF);
            canvas.drawCircle(bounds.width() * .16f, bounds.height() * .15f, dp(58), paint);
            canvas.drawCircle(bounds.width() * .76f, bounds.height() * .10f, dp(78), paint);
            paint.setColor(0x334FC7E8);
            canvas.drawCircle(bounds.width() * .90f, bounds.height() * .30f, dp(42), paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
