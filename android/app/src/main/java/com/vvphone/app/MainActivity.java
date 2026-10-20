package com.vvphone.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VVPhone 的 Android 外壳。
 *
 * 1. 以 https://appassets.androidplatform.net 作为本地资源源站，保证 IndexedDB / localStorage
 *    在稳定的 https 源下工作，导入导出（FileReader、Blob 下载）行为与浏览器一致。
 * 2. 系统状态栏与导航栏全部隐藏（沉浸式），并适配刘海屏与软键盘。
 * 3. 注入脚本接管 &lt;a download&gt; 的 blob:/data: 下载，通过 JS Bridge 分块写入「下载/VVPhone」。
 */
public class MainActivity extends Activity {

    private static final String TAG = "VVPhone";
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE_PERMISSION = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, OutputStream> downloadStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Uri> downloadUris = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> downloadPaths = new ConcurrentHashMap<>();
    private final AtomicInteger downloadSeq = new AtomicInteger(1);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupWindow();
        applyCutoutMode();

        String recentCrash = CrashStore.readLastCrash(this);
        int crashCount = CrashStore.crashCount(this);

        // 连续崩溃时进入安全模式：本次不加载页面，保证用户至少能看到内容并反馈。
        if (recentCrash != null && crashCount >= 2) {
            showFatalScreen("VVPhone 连续 " + crashCount + " 次异常退出",
                    "已进入安全模式：本次不加载页面，避免再次闪退。\n\n"
                            + "点「复制信息」把下面的内容反馈；点「清空记录并重试」可再按正常流程启动一次。\n\n"
                            + recentCrash,
                    "清空记录并重试",
                    () -> {
                        CrashStore.clear(this);
                        startWebViewPhase();
                    });
            return;
        }

        // 上一次运行异常退出：先给出可复制的堆栈页，而不是再次不明不白地闪退。
        if (recentCrash != null) {
            showFatalScreen("VVPhone 上次异常退出",
                    "下面是上次崩溃时的信息（可复制后反馈）。\n\n" + recentCrash,
                    "继续使用",
                    () -> {
                        CrashStore.clear(this);
                        startWebViewPhase();
                    });
            return;
        }

        startWebViewPhase();
    }

    /**
     * 先显示一个最朴素的启动页，再创建 WebView。
     * 这样即使 WebView 阶段崩了，也能从「是否看到过启动页」判断崩溃发生在哪一层。
     */
    private void startWebViewPhase() {
        showSplash();
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (!createWebView()) {
                return;
            }
            requestLegacyStoragePermission();
            // 只有稳定运行一段时间才认为启动成功，否则一崩就清零会导致永远进不了安全模式。
            mainHandler.postDelayed(this::markHealthy, 8000L);
        }, 220L);
    }

    private void showSplash() {
        try {
            TextView splash = new TextView(this);
            splash.setBackgroundColor(Color.BLACK);
            splash.setTextColor(Color.parseColor("#9AA0AE"));
            splash.setTextSize(13f);
            splash.setGravity(Gravity.CENTER);
            splash.setText("VVPhone " + appVersionLabel() + " 正在启动…\n\nAndroid " + Build.VERSION.RELEASE
                    + " · API " + Build.VERSION.SDK_INT
                    + "\nWebView " + webViewLabel());
            setContentView(splash);
        } catch (Throwable ignored) {
            // 启动页失败不能影响主流程
        }
    }

    /** 启动页 / 错误页上显示版本号，方便确认「是否真的装上了新包」。 */
    @SuppressWarnings("deprecation")
    private String appVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private String webViewLabel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null) {
                    return info.packageName + " " + info.versionName;
                }
            }
        } catch (Throwable ignored) {
            // 部分 ROM 会抛异常
        }
        return "unknown";
    }

    private String diagnostics() {
        return "app: " + appVersionLabel()
                + "\nandroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nmodel: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nwebview: " + webViewLabel() + "\n";
    }

    private void markHealthy() {
        if (webView == null || isFinishing() || isDestroyed()) {
            return;
        }
        CrashStore.clear(this);
    }

    private void applyCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(params);
        }
    }

    private void requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERMISSION);
        }
    }

    /**
     * 创建（或重建）WebView 并加载页面。初始化异常会被捕获并展示为错误页，
     * 避免用户只看到「闪退」而拿不到任何线索。
     */
    private boolean createWebView() {
        try {
            WebView.setWebContentsDebuggingEnabled(true);
            WebView view = new SwipeBackWebView(this, this::handleBackAction);
            view.setBackgroundColor(Color.BLACK);
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
            configureWebView(view);
            setContentView(view);
            webView = view;
            view.loadUrl(START_URL);
            return true;
        } catch (Throwable error) {
            webView = null;
            Log.e(TAG, "WebView 初始化失败", error);
            CrashStore.record(this, error);
            showFatalScreen("VVPhone 启动失败",
                    "WebView 初始化失败（多数是系统 WebView 被禁用或版本过低）：\n\n"
                            + CrashStore.describe(error)
                            + "\n\n处理办法：在应用商店更新「Android System WebView」或「Chrome」，然后重试。",
                    "重新加载",
                    this::startWebViewPhase);
            return false;
        }
    }

    // 崩溃记录的实现在 CrashStore 中，由 VvApp 在进程最早阶段安装。

    private void showFatalScreen(String titleText, String message, String actionLabel, Runnable action) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.parseColor("#101014"));
            root.setPadding(dp(16), dp(28), dp(16), dp(16));

            TextView title = new TextView(this);
            title.setText(titleText);
            title.setTextColor(Color.WHITE);
            title.setTextSize(18f);
            root.addView(title);

            TextView hint = new TextView(this);
            hint.setText("点「复制信息」把内容发给开发者；也可以点右边按钮再试一次。");
            hint.setTextColor(Color.parseColor("#8A8A99"));
            hint.setTextSize(12f);
            hint.setPadding(0, dp(8), 0, dp(8));
            root.addView(hint);

            ScrollView scroll = new ScrollView(this);
            TextView body = new TextView(this);
            body.setText(message + "\n\n" + diagnostics());
            body.setTextColor(Color.parseColor("#C8C8D2"));
            body.setTextSize(11f);
            body.setTextIsSelectable(true);
            body.setPadding(0, dp(8), 0, dp(8));
            scroll.addView(body);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.addView(makeButton("复制信息",
                    () -> copyToClipboard(diagnostics() + "\n" + titleText + "\n\n" + message)), weightParams());
            buttons.addView(makeButton(actionLabel, action), weightParams());
            root.addView(buttons);

            setContentView(root);
        } catch (Throwable ignored) {
            // 错误页本身失败时保持黑底，绝不能二次崩溃
        }
    }

    private Button makeButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("VVPhone", text));
                showToast("已复制错误信息");
            }
        } catch (Throwable ignored) {
            showToast("复制失败，请手动截图");
        }
    }

    private void setupWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        applyImmersiveMode();
    }

    /** 全屏沉浸式：隐藏系统状态栏与导航栏，上滑可临时呼出。 */
    private void applyImmersiveMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (webView != null) {
            webView.onResume();
        }
    }
    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // 用户可能填写 http 协议的模型接口，本地源是 https，需要放开混合内容限制。
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        view.addJavascriptInterface(new NativeBridge(), "VVAndroid");
        view.setWebViewClient(new LocalAssetClient());
        view.setWebChromeClient(new AppChromeClient());
        view.setDownloadListener(new AppDownloadListener());
        applyWindowInsets(view);
    }

    /**
     * 全屏后自行处理刘海与软键盘占位：避免顶部内容被摄像头挖孔遮挡、底部输入框被键盘覆盖。
     */
    private void applyWindowInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = cutout.top;
                bottom = ime.bottom;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    top = cutout.getSafeInsetTop();
                }
            }
            if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPadding(0, top, 0, bottom);
            }
            return insets;
        });
        view.requestApplyInsets();
    }

    /** 把 assets/www 通过 https 虚拟域名提供，替代 file:// 加载。 */
    private final class LocalAssetClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri != null && APP_HOST.equals(uri.getHost())) {
                String path = uri.getPath();
                if (path == null || path.isEmpty()) {
                    path = "/assets/index.html";
                }
                if (!path.startsWith("/assets/")) {
                    path = "/assets" + path;
                }
                String assetPath = "www/" + path.substring("/assets/".length());
                if (assetPath.endsWith("/")) {
                    assetPath = assetPath + "index.html";
                }
                try {
                    InputStream stream = getAssets().open(assetPath);
                    String mime = guessMimeType(assetPath);
                    String charset = isTextMime(mime) ? "utf-8" : null;
                    WebResourceResponse response = new WebResourceResponse(mime, charset, stream);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-cache");
                    headers.put("Access-Control-Allow-Origin", "*");
                    response.setResponseHeaders(headers);
                    return response;
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(("资源未找到: " + assetPath).getBytes(StandardCharsets.UTF_8)));
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null || APP_HOST.equals(uri.getHost())) {
                return false;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            try {
                view.evaluateJavascript(INJECT_JS, null);
            } catch (Throwable error) {
                Log.w(TAG, "注入脚本失败", error);
            }
        }

        /**
         * WebView 渲染进程崩溃（页面过重、内存不足等）默认会连带把整个 App 杀掉，
         * 也就是用户看到的「闪退」。接管后自动重建 WebView，数据仍保留在 IndexedDB 里。
         */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.e(TAG, "渲染进程已终止，重建 WebView");
            if (view != webView) {
                return true;
            }
            webView = null;
            try {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            try {
                view.destroy();
            } catch (Throwable ignored) {
                // 忽略
            }
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (createWebView()) {
                    showToast("页面已自动恢复");
                }
            });
            return true;
        }
    }
    /** 文件选择器：导入备份/主题预设/图片都依赖这个回调。 */
    private final class AppChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            filePathCallback = callback;

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            if (params != null) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            }
            try {
                startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                return true;
            } catch (Exception e) {
                filePathCallback = null;
                showToast("无法打开文件选择器");
                return false;
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            if (message != null) {
                Log.d(TAG, message.message() + " @" + message.sourceId() + ":" + message.lineNumber());
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();
                if (clipData != null && clipData.getItemCount() > 0) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** http(s) 直链交给系统 DownloadManager，blob:/data: 交给注入脚本。 */
    private final class AppDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimetype, long contentLength) {
            if (url == null) {
                return;
            }
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                String js = "(function(){try{if(window.__vvSaveUrl){window.__vvSaveUrl("
                        + jsString(url) + "," + jsString(guessFileNameFromUrl(url, mimetype))
                        + ");}}catch(e){}})();";
                webView.evaluateJavascript(js, null);
                return;
            }
            try {
                String fileName = guessFileNameFromUrl(url, mimetype);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                if (userAgent != null) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                request.setTitle(fileName);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "VVPhone/" + fileName);
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                }
                showToast("已开始下载：" + fileName);
            } catch (Exception e) {
                Log.w(TAG, "DownloadManager 下载失败", e);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    showToast("下载失败，请检查网络");
                }
            }
        }
    }
    /** 注入到页面的 window.VVAndroid：分块写入下载文件。 */
    private final class NativeBridge {

        @JavascriptInterface
        public String startDownload(String fileName, String mimeType) {
            try {
                String safeName = sanitizeFileName(fileName);
                String id = "vv_" + downloadSeq.getAndIncrement() + "_" + System.currentTimeMillis();
                String mime = (mimeType == null || mimeType.trim().isEmpty())
                        ? guessMimeType(safeName) : mimeType;

                OutputStream stream;
                Uri uri = null;
                String displayPath;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/VVPhone");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        return "ERR:无法创建下载文件";
                    }
                    stream = getContentResolver().openOutputStream(uri);
                    displayPath = "下载/VVPhone/" + safeName;
                } else {
                    File dir = resolveLegacyDownloadDir();
                    if (dir == null) {
                        return "ERR:没有可写入的存储目录";
                    }
                    File target = uniqueFile(dir, safeName);
                    stream = new FileOutputStream(target);
                    displayPath = target.getAbsolutePath();
                }

                if (stream == null) {
                    if (uri != null) {
                        getContentResolver().delete(uri, null, null);
                    }
                    return "ERR:无法写入文件";
                }

                downloadStreams.put(id, stream);
                if (uri != null) {
                    downloadUris.put(id, uri);
                }
                downloadPaths.put(id, displayPath);
                return id;
            } catch (Exception e) {
                Log.w(TAG, "创建下载文件失败", e);
                return "ERR:" + (e.getMessage() == null ? "创建文件失败" : e.getMessage());
            }
        }

        @JavascriptInterface
        public boolean writeChunk(String id, String base64Chunk) {
            OutputStream stream = downloadStreams.get(id);
            if (stream == null || base64Chunk == null) {
                return false;
            }
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                synchronized (stream) {
                    stream.write(bytes);
                }
                return true;
            } catch (Exception e) {
                Log.w(TAG, "写入下载分块失败", e);
                return false;
            }
        }

        @JavascriptInterface
        public boolean finishDownload(String id) {
            OutputStream stream = downloadStreams.remove(id);
            Uri uri = downloadUris.remove(id);
            String path = downloadPaths.remove(id);
            boolean ok = stream != null;
            if (stream != null) {
                try {
                    stream.flush();
                    stream.close();
                } catch (Exception ignored) {
                    ok = false;
                }
            }
            if (ok && uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                } catch (Exception ignored) {
                    // 文件已写入，仅 pending 标记更新失败时不阻断流程
                }
            }
            showToast(ok ? "已保存到 " + (path == null ? "下载/VVPhone" : path) : "保存失败");
            return ok;
        }

        @JavascriptInterface
        public void toast(String message) {
            showToast(message);
        }
    }
    private File resolveLegacyDownloadDir() {
        boolean canWritePublic = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (canWritePublic) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "VVPhone");
            if (dir.exists() || dir.mkdirs()) {
                return dir;
            }
        }
        File fallback = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VVPhone");
        if (fallback.exists() || fallback.mkdirs()) {
            return fallback;
        }
        return null;
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) {
            return file;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int index = 1;
        while (file.exists() && index < 1000) {
            file = new File(dir, base + "(" + index + ")" + ext);
            index++;
        }
        return file;
    }

    private static String sanitizeFileName(String name) {
        String base = (name == null || name.trim().isEmpty()) ? "vvphone_download" : name.trim();
        base = base.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_');
        if (base.length() > 120) {
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0 && base.length() - dot <= 10) {
                ext = base.substring(dot);
            }
            base = base.substring(0, 100) + ext;
        }
        return base;
    }
    private static String guessFileNameFromUrl(String url, String mime) {
        try {
            String segment = Uri.parse(url).getLastPathSegment();
            if (segment != null && !segment.isEmpty()) {
                return sanitizeFileName(segment);
            }
        } catch (Exception ignored) {
            // 落到下面的默认命名
        }
        return sanitizeFileName("vvphone_" + System.currentTimeMillis() + guessExtension(mime));
    }

    private static String guessExtension(String mime) {
        if (mime == null) {
            return ".bin";
        }
        String value = mime.toLowerCase();
        if (value.contains("json")) {
            return ".json";
        }
        if (value.contains("jpeg") || value.contains("jpg")) {
            return ".jpg";
        }
        if (value.contains("png")) {
            return ".png";
        }
        if (value.contains("plain")) {
            return ".txt";
        }
        if (value.contains("zip")) {
            return ".zip";
        }
        if (value.contains("mp4")) {
            return ".mp4";
        }
        if (value.contains("mpeg")) {
            return ".mp3";
        }
        return ".bin";
    }

    private static String guessMimeType(String path) {
        String value = path == null ? "" : path.toLowerCase();
        if (value.endsWith(".html") || value.endsWith(".htm")) {
            return "text/html";
        }
        if (value.endsWith(".js") || value.endsWith(".mjs")) {
            return "application/javascript";
        }
        if (value.endsWith(".css")) {
            return "text/css";
        }
        if (value.endsWith(".json")) {
            return "application/json";
        }
        if (value.endsWith(".png")) {
            return "image/png";
        }
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (value.endsWith(".gif")) {
            return "image/gif";
        }
        if (value.endsWith(".webp")) {
            return "image/webp";
        }
        if (value.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (value.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (value.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (value.endsWith(".woff")) {
            return "font/woff";
        }
        if (value.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (value.endsWith(".otf")) {
            return "font/otf";
        }
        if (value.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (value.endsWith(".wav")) {
            return "audio/wav";
        }
        if (value.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (value.endsWith(".txt") || value.endsWith(".md")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static boolean isTextMime(String mime) {
        if (mime == null) {
            return false;
        }
        return mime.startsWith("text/")
                || mime.contains("javascript")
                || mime.contains("json")
                || mime.contains("svg");
    }

    private static String jsString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        return builder.append('"').toString();
    }

    private void showToast(String message) {
        if (message == null) {
            return;
        }
        mainHandler.post(() -> {
            try {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
                // Activity 已销毁时忽略
            }
        });
    }

    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    /**
     * 返回逻辑（返回键与「左边缘右滑」手势共用）：
     * 先让页面自己处理（退回桌面 / 关闭弹层），页面不处理时再退 WebView 历史，最后退到后台。
     */
    private void handleBackAction() {
        WebView view = webView;
        if (view == null) {
            moveTaskToBack(true);
            return;
        }
        try {
            view.evaluateJavascript(
                    "(function(){try{return typeof window.__vvHandleBack==='function'"
                            + "?(window.__vvHandleBack()?'1':'0'):'0';}catch(e){return '0';}})();",
                    value -> {
                        if (value != null && value.contains("1")) {
                            return;
                        }
                        WebView current = webView;
                        if (current != null && current.canGoBack()) {
                            current.goBack();
                        } else {
                            moveTaskToBack(true);
                        }
                    });
        } catch (Throwable error) {
            Log.w(TAG, "返回处理失败", error);
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        for (OutputStream stream : downloadStreams.values()) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // 退出时忽略清理错误
            }
        }
        downloadStreams.clear();
        downloadUris.clear();
        downloadPaths.clear();
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Exception ignored) {
                // 忽略
            }
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 支持「从屏幕左边缘向右滑动返回」的 WebView。
     *
     * 只在起手点落在左边缘 28dp 内时才跟踪，避免和页面内部的横向滑动（轮播、列表横滑）冲突；
     * 拖动过程中 WebView 跟随手指平移，松手超过 72dp 就触发返回，不到阈值则弹回原位。
     */
    private static final class SwipeBackWebView extends WebView {

        private final float edgePx;
        private final float triggerPx;
        private final int slopPx;
        private final Runnable onSwipeBack;

        private float downX;
        private float downY;
        private boolean tracking;
        private boolean dragging;
        private boolean fired;

        SwipeBackWebView(Context context, Runnable onSwipeBack) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            this.edgePx = 28f * density;
            this.triggerPx = 72f * density;
            this.slopPx = ViewConfiguration.get(context).getScaledTouchSlop();
            this.onSwipeBack = onSwipeBack;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    tracking = downX <= edgePx;
                    dragging = false;
                    fired = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (fired) {
                        return true;
                    }
                    if (tracking) {
                        float dx = event.getX() - downX;
                        float dy = Math.abs(event.getY() - downY);
                        if (dx <= 0 || (dy > dx && dy > slopPx)) {
                            // 竖直滚动或反向滑动：交还给页面处理
                            tracking = false;
                            stopDrag();
                        } else if (dx > slopPx && dx > dy) {
                            if (!dragging) {
                                dragging = true;
                                cancelWebViewGesture(event);
                                if (getParent() instanceof ViewGroup) {
                                    ((ViewGroup) getParent()).requestDisallowInterceptTouchEvent(true);
                                }
                            }
                            setTranslationX(dx * 0.55f);
                            if (dx >= triggerPx) {
                                fired = true;
                                tracking = false;
                                stopDrag();
                                if (onSwipeBack != null) {
                                    onSwipeBack.run();
                                }
                            }
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!fired) {
                        stopDrag();
                    }
                    tracking = false;
                    dragging = false;
                    fired = false;
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        private void stopDrag() {
            if (getTranslationX() != 0f) {
                setTranslationX(0f);
            }
        }

        /** 手势被接管时给 WebView 补一个 CANCEL，避免页面停留在「按下未抬起」的状态。 */
        private void cancelWebViewGesture(MotionEvent source) {
            try {
                MotionEvent cancel = MotionEvent.obtain(source);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel);
                cancel.recycle();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /**
     * 注入到页面的适配脚本：
     * 1. 强制无边框全屏（App 内始终全屏）；
     * 2. 返回键先回桌面；
     * 3. 接管 &lt;a download&gt; 的 blob:/data: 下载，交给原生写入「下载/VVPhone」。
     */
    /**
     * 拼接多行脚本。这里刻意不用 String.join —— 它是 Java 8 API，在 Android 上
     * 需要 API 26 才存在，而 minSdk 是 24，运行到 Android 7.x 会 NoSuchMethodError 闪退。
     */
    private static String joinLines(String... lines) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private static final String INJECT_JS = joinLines(
            "(function () {",
            "  if (window.__vvNativeReady) { return; }",
            "  window.__vvNativeReady = true;",
            "  var BRIDGE = window.VVAndroid || null;",
            "",
            "  /* ---------- 1. 无边框全屏 ---------- */",
            "  var STYLE_ID = 'vv-native-fullscreen-style';",
            "  function forceFullscreen() {",
            "    try {",
            "      var body = document.body;",
            "      if (body && !body.classList.contains('fullscreen-mode')) {",
            "        body.classList.add('fullscreen-mode');",
            "      }",
            "    } catch (e) { }",
            "  }",
            "  if (!document.getElementById(STYLE_ID)) {",
            "    var style = document.createElement('style');",
            "    style.id = STYLE_ID;",
            "    style.textContent = [",
            "      'html,body{height:100%!important;max-height:100%!important;overflow:hidden!important;}',",
            "      'body.fullscreen-mode .phone-case{width:100%!important;height:100%!important;border:none!important;border-radius:0!important;box-shadow:none!important;}',",
            "      'input,textarea,[contenteditable=true]{user-select:text!important;-webkit-user-select:text!important;}'",
            "    ].join('');",
            "    (document.head || document.documentElement).appendChild(style);",
            "  }",
            "  forceFullscreen();",
            "  if (document.body && typeof MutationObserver === 'function') {",
            "    new MutationObserver(forceFullscreen).observe(document.body, { attributes: true, attributeFilter: ['class'] });",
            "  }",
            "",
            "  /* ---------- 2. 返回（返回键 / 左边缘右滑）：弹层 → 聊天 → 桌面 → 后台 ---------- */",
            "  var OVERLAY_IDS = ['ctx-overlay', 'msg-context-menu', 'chat-settings-modal',",
            "    'transfer-modal', 'thoughts-modal', 'offline-settings-modal',",
            "    'calendar-event-modal', 'image-modal', 'video-modal', 'voice-modal'];",
            "  function anyOverlayOpen() {",
            "    for (var i = 0; i < OVERLAY_IDS.length; i++) {",
            "      var el = document.getElementById(OVERLAY_IDS[i]);",
            "      if (el && el.classList.contains('active')) { return true; }",
            "    }",
            "    return false;",
            "  }",
            "  window.__vvHandleBack = function () {",
            "    try {",
            "      if (anyOverlayOpen() && typeof window.closeAllOverlays === 'function') {",
            "        window.closeAllOverlays();",
            "        return true;",
            "      }",
            "      var chat = document.getElementById('chat-interface');",
            "      if (chat && chat.style.display === 'flex' && typeof window.closeChat === 'function') {",
            "        window.closeChat();",
            "        return true;",
            "      }",
            "      var home = document.getElementById('home-screen');",
            "      if (home && home.classList.contains('active')) { return false; }",
            "      if (typeof window.goHome === 'function') { window.goHome(); return true; }",
            "    } catch (e) { }",
            "    return false;",
            "  };",
            "",
            "  /* ---------- 3. 导出下载 ---------- */",
            "  function notify(message) {",
            "    try { if (BRIDGE && BRIDGE.toast) { BRIDGE.toast(String(message)); } } catch (e) { }",
            "  }",
            "  function toBase64(bytes) {",
            "    var binary = '';",
            "    var SIZE = 0x8000;",
            "    for (var i = 0; i < bytes.length; i += SIZE) {",
            "      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + SIZE));",
            "    }",
            "    return btoa(binary);",
            "  }",
            "  function readArrayBuffer(blob) {",
            "    return new Promise(function (resolve, reject) {",
            "      var reader = new FileReader();",
            "      reader.onload = function () { resolve(reader.result); };",
            "      reader.onerror = function () { reject(reader.error || new Error('读取失败')); };",
            "      reader.readAsArrayBuffer(blob);",
            "    });",
            "  }",
            "  function saveUrl(url, name) {",
            "    if (!BRIDGE) { notify('当前环境不支持保存文件'); return Promise.resolve(false); }",
            "    return fetch(url).then(function (response) { return response.blob(); }).then(function (blob) {",
            "      var id = BRIDGE.startDownload(name || ('vvphone_' + Date.now()), blob.type || 'application/octet-stream');",
            "      if (!id || String(id).indexOf('ERR:') === 0) {",
            "        throw new Error(String(id || '').replace('ERR:', '') || '无法创建文件');",
            "      }",
            "      var CHUNK = 384 * 1024;",
            "      var offset = 0;",
            "      function step() {",
            "        if (offset >= blob.size) {",
            "          BRIDGE.finishDownload(id);",
            "          return Promise.resolve(true);",
            "        }",
            "        var part = blob.slice(offset, Math.min(offset + CHUNK, blob.size));",
            "        offset += CHUNK;",
            "        return readArrayBuffer(part).then(function (buffer) {",
            "          if (!BRIDGE.writeChunk(id, toBase64(new Uint8Array(buffer)))) {",
            "            throw new Error('写入文件失败');",
            "          }",
            "          return step();",
            "        });",
            "      }",
            "      return step();",
            "    }).catch(function (error) {",
            "      notify('保存失败：' + (error && error.message ? error.message : error));",
            "      return false;",
            "    });",
            "  }",
            "  window.__vvSaveUrl = saveUrl;",
            "",
            "  /* ---------- 4. 保护导出中的 blob URL（页面可能在 click 后立即 revoke）---------- */",
            "  var nativeRevoke = URL.revokeObjectURL;",
            "  var vvHolds = {};",
            "  var vvRevoked = {};",
            "  function vvHold(url) { if (url) { vvHolds[url] = (vvHolds[url] || 0) + 1; } }",
            "  function vvRelease(url) {",
            "    if (!url) { return; }",
            "    var count = (vvHolds[url] || 0) - 1;",
            "    if (count > 0) { vvHolds[url] = count; return; }",
            "    delete vvHolds[url];",
            "    if (vvRevoked[url]) {",
            "      delete vvRevoked[url];",
            "      try { nativeRevoke.call(URL, url); } catch (e) { }",
            "    }",
            "  }",
            "  if (typeof nativeRevoke === 'function') {",
            "    URL.revokeObjectURL = function (url) {",
            "      if (url && vvHolds[url]) { vvRevoked[url] = true; return; }",
            "      try { nativeRevoke.call(URL, url); } catch (e) { }",
            "    };",
            "  }",
            "",
            "  var nativeClick = HTMLAnchorElement.prototype.click;",
            "  HTMLAnchorElement.prototype.click = function () {",
            "    try {",
            "      var downloadName = this.download;",
            "      var rawHref = this.getAttribute('href') || '';",
            "      var isBlob = rawHref.indexOf('blob:') === 0;",
            "      if (downloadName && (isBlob || rawHref.indexOf('data:') === 0)) {",
            "        if (isBlob) { vvHold(rawHref); }",
            "        saveUrl(rawHref, downloadName).then(function () {",
            "          if (isBlob) { vvRelease(rawHref); }",
            "        }, function () {",
            "          if (isBlob) { vvRelease(rawHref); }",
            "        });",
            "        return;",
            "      }",
            "    } catch (e) { }",
            "    return nativeClick.apply(this, arguments);",
            "  };",
            "})();");
}
