package com.deepseeklover.landrop;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PICK_FILES = 11, PICK_TREE = 12, EXPORT_FILE = 13, PERMISSIONS = 14;
    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService commands = Executors.newFixedThreadPool(4);
    private final Map<String, Uri> selections = new ConcurrentHashMap<>();
    private String fileRequest, directoryRequest;
    private Uri exportSource;
    private boolean loaded, destroyed, starting;
    private String origin;
    private Intent pendingShare;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(248, 251, 248)); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(248, 251, 248));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        web = new WebView(this); root.addView(web, new LinearLayout.LayoutParams(-1, -1)); setContentView(root);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setBackgroundColor(Color.rgb(248, 251, 248)); web.addJavascriptInterface(new Bridge(), "LanDropAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The native bridge only belongs to bundled pages. Never navigate to a peer or an external site.
                return origin == null || !request.getUrl().toString().equals(origin + "/");
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (origin != null && uri.toString().startsWith(origin + "/")) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView view, String url) { loaded = true; deliverShare(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton(isEnglish() ? "Confirm" : "确认", (d, w) -> result.confirm())
                        .setNegativeButton(isEnglish() ? "Cancel" : "取消", (d, w) -> result.cancel()).setOnCancelListener(d -> result.cancel()).show(); return true;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage message) { android.util.Log.d("LanDropWeb", message.message()); return true; }
        });
        if (isShare(getIntent())) pendingShare = getIntent();
        ensurePermissions();
    }
    private boolean isEnglish() { TransferService service = TransferService.instance; return service != null && service.engine != null && service.engine.language().equals("en"); }
    private void ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) needed.add("android.permission.ACCESS_LOCAL_NETWORK");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), PERMISSIONS); else startReceiver();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSIONS) return;
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("LanDrop").setMessage("局域网传输需要“附近的设备 / 本地网络”权限。请在应用设置中允许后重试。")
                    .setPositiveButton("打开设置", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("稍后重试", (d, w) -> {}).show();
        } else startReceiver();
    }
    private void startReceiver() {
        if (destroyed || starting) return;
        starting = true;
        TransferService.startupError = null;
        startForegroundService(new Intent(this, TransferService.class)); awaitReceiver();
    }
    private void awaitReceiver() {
        if (destroyed || loaded) return;
        TransferService service = TransferService.instance;
        if (service != null && service.engine != null) { starting = false; origin = "http://127.0.0.1:" + service.engine.port(); web.loadUrl(origin + "/"); return; }
        if (TransferService.startupError != null) {
            starting = false;
            new AlertDialog.Builder(this).setTitle("LanDrop 启动失败").setMessage(TransferService.startupError).setPositiveButton("重试", (d, w) -> startReceiver()).setNegativeButton("关闭", (d, w) -> finish()).show(); return;
        }
        handler.postDelayed(this::awaitReceiver, 150);
    }
    @Override protected void onResume() {
        super.onResume();
        if (web == null) return;
        web.onResume();
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) return;
        if (loaded && TransferService.instance == null) { loaded = false; startReceiver(); }
        else if (!loaded && TransferService.instance == null) startReceiver();
    }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null); commands.shutdownNow(); selections.clear();
        if (web != null) { web.removeJavascriptInterface("LanDropAndroid"); web.destroy(); } super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web != null && loaded) web.evaluateJavascript("window.androidBack && window.androidBack()", result -> { if ("false".equals(result) || "null".equals(result)) moveTaskToBack(true); });
        else super.onBackPressed();
    }
    private LanDropEngine engine() throws IOException {
        TransferService service = TransferService.instance;
        if (service == null || service.engine == null) throw new IOException("传输服务已停止，请重新打开应用"); return service.engine;
    }
    private final class Bridge {
        @JavascriptInterface public void postMessage(String message) {
            if (destroyed || message == null || message.length() > 200000) return;
            commands.execute(() -> {
                String requestId = "";
                try {
                    JSONObject request = new JSONObject(message); requestId = request.getString("id");
                    String action = request.getString("action"); JSONObject data = request.optJSONObject("data"); if (data == null) data = new JSONObject();
                    LanDropEngine engine = engine(); JSONObject result;
                    switch (action) {
                        case "state": result = engine.state(); break;
                        case "api": {
                            String route = data.getString("route"), method = data.optString("method", "POST"); JSONObject body = data.optJSONObject("body"); if (body == null) body = new JSONObject();
                            if (route.equals("/api/peers")) result = engine.addPeer(body.getString("address"));
                            else if (route.equals("/api/scan")) { engine.scan(); result = new JSONObject().put("ok", true); }
                            else if (route.equals("/api/text")) result = new JSONObject().put("id", engine.sendText(body.getString("peerId"), body.getString("text")));
                            else result = engine.command(route, method, body); break;
                        }
                        case "selectFiles": {
                            String callback = requestId;
                            handler.post(() -> {
                                if (fileRequest != null) { respond(callback, null, "文件选择器已打开"); return; }
                                fileRequest = callback;
                                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                                try { startActivityForResult(pick, PICK_FILES); } catch (Exception e) { fileRequest = null; respond(callback, null, "系统文件选择器不可用"); }
                            }); return;
                        }
                        case "sendFiles": {
                            String peerId = data.getString("peerId"); JSONArray files = data.getJSONArray("files");
                            for (int i = 0; i < files.length(); i++) {
                                String key = files.getString(i); Uri uri = selections.get(key); if (uri == null) throw new IOException("文件选择已失效，请重新选择");
                                JSONObject meta = metadata(uri, false); long size = meta.getLong("size");
                                engine.sendFile(peerId, meta.getString("name"), size, () -> { InputStream input = getContentResolver().openInputStream(uri); if (input == null) throw new IOException("无法读取文件"); return input; });
                            }
                            result = new JSONObject().put("ok", true); break;
                        }
                        case "chooseFolder": {
                            if (engine.hasActiveTransfers()) throw new IOException("请先完成或取消传输，再更改接收文件夹");
                            String callback = requestId;
                            handler.post(() -> {
                                if (directoryRequest != null) { respond(callback, null, "文件夹选择器已打开"); return; }
                                directoryRequest = callback;
                                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                                try { startActivityForResult(pick, PICK_TREE); } catch (Exception e) { directoryRequest = null; respond(callback, null, "系统文件夹选择器不可用"); }
                            }); return;
                        }
                        case "copyText": { String text = data.getString("text"); handler.post(() -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("LanDrop", text))); result = new JSONObject().put("ok", true); break; }
                        case "pasteText": {
                            String callback = requestId;
                            handler.post(() -> {
                                try { ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE); ClipData clip = clipboard.getPrimaryClip(); String text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(MainActivity.this).toString() : ""; respond(callback, new JSONObject().put("text", text), null); }
                                catch (Exception e) { respond(callback, null, "无法读取剪贴板，请长按输入框粘贴"); }
                            }); return;
                        }
                        case "openExternal": {
                            String url = data.getString("url"), callback = requestId;
                            if (!Arrays.asList("https://space.bilibili.com/521952225", "https://github.com/Hjnandlizhiyan", "https://www.deepseeklover.com").contains(url)) throw new IOException("无法打开此链接");
                            handler.post(() -> {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
                                    respond(callback, new JSONObject().put("ok", true), null);
                                } catch (Exception e) { respond(callback, null, "未找到可打开网页的浏览器"); }
                            }); return;
                        }
                        case "showFile": { JSONObject job = engine.completedJob(data.getString("id")); handler.post(() -> showFile(job)); result = new JSONObject().put("ok", true); break; }
                        case "stop": { handler.post(() -> { stopService(new Intent(MainActivity.this, TransferService.class)); finishAndRemoveTask(); }); result = new JSONObject().put("ok", true); break; }
                        default: throw new IOException("未知操作");
                    }
                    respond(requestId, result, null);
                } catch (Exception e) { respond(requestId, null, LanDropEngine.friendlyError(e)); }
            });
        }
    }
    private void respond(String requestId, Object result, String error) {
        try {
            JSONObject message = new JSONObject().put("id", requestId).put("result", result == null ? JSONObject.NULL : result);
            if (error != null) message.put("error", error);
            String script = "window.nativeResponse(" + JSONObject.quote(message.toString()) + ")";
            handler.post(() -> { if (!destroyed && web != null) web.evaluateJavascript(script, null); });
        } catch (Exception ignored) { }
    }
    private JSONObject metadata(Uri uri) throws Exception {
        return metadata(uri, true);
    }
    private JSONObject metadata(Uri uri, boolean remember) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("请选择系统文件选择器中的本机文件");
        String name = "file"; long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { name = cursor.getString(0); if (!cursor.isNull(1)) size = cursor.getLong(1); }
        }
        if (size < 0) try (android.content.res.AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) { if (descriptor != null) size = descriptor.getLength(); }
        if (size < 0) throw new IOException("无法确定文件大小，请将文件下载到本机后重试");
        if (size > LanDropEngine.MAX_FILE) throw new IOException("单个文件上限为 20 GB");
        String key = UUID.randomUUID().toString(); if (remember) selections.put(key, uri);
        return new JSONObject().put("name", name).put("size", size).put("path", key);
    }
    @Override protected void onActivityResult(int code, int resultCode, Intent data) {
        super.onActivityResult(code, resultCode, data);
        if (code == PICK_FILES) {
            String callback = fileRequest; fileRequest = null;
            commands.execute(() -> {
                JSONArray files = new JSONArray();
                try {
                    if (resultCode == RESULT_OK && data != null) {
                        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) files.put(metadata(data.getClipData().getItemAt(i).getUri()));
                        else if (data.getData() != null) files.put(metadata(data.getData()));
                    }
                    respond(callback, files, null);
                } catch (Exception e) { respond(callback, null, LanDropEngine.friendlyError(e)); }
            });
        } else if (code == PICK_TREE) {
            String callback = directoryRequest; directoryRequest = null;
            commands.execute(() -> {
                try {
                    if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                        Uri tree = data.getData(); int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        if ((flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) throw new IOException("接收文件夹不可写，请重新选择");
                        getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        LanDropEngine engine = engine(); engine.setStorage(new TreeStorage(getContentResolver(), tree)); engine.setWarning("");
                        getSharedPreferences("android", MODE_PRIVATE).edit().putString("receiveTree", tree.toString()).apply();
                    }
                    respond(callback, new JSONObject().put("ok", true), null);
                } catch (Exception e) { respond(callback, null, LanDropEngine.friendlyError(e)); }
            });
        } else if (code == EXPORT_FILE && resultCode == RESULT_OK && data != null && exportSource != null) {
            Uri source = exportSource, destination = data.getData(); exportSource = null;
            commands.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (input == null || output == null) throw new IOException("无法另存文件"); byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                    handler.post(() -> Toast.makeText(this, isEnglish() ? "File saved" : "文件已另存", Toast.LENGTH_LONG).show());
                } catch (Exception e) { handler.post(() -> Toast.makeText(this, isEnglish() ? "Save failed" : "另存失败", Toast.LENGTH_LONG).show()); }
            });
        }
    }
    private Uri receivedUri(JSONObject job) throws Exception {
        String path = job.getString("path");
        if (path.startsWith("content://")) return Uri.parse(path);
        File file = new File(path); if (!file.isFile()) throw new IOException("接收文件已被移动或删除"); return ReceivedFileProvider.uri(this, file);
    }
    private void showFile(JSONObject job) {
        try {
            Uri uri = receivedUri(job); String name = job.getString("name");
            String ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_")); String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT)); if (mime == null) mime = "application/octet-stream";
            final String type = mime;
            String[] actions = isEnglish() ? new String[]{"Open file", "Share file", "Save a copy…"} : new String[]{"打开文件", "分享文件", "另存到…"};
            new AlertDialog.Builder(this).setTitle(name).setItems(actions, (dialog, which) -> {
                try {
                    if (which == 0) startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    else if (which == 1) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "LanDrop"));
                    else { exportSource = uri; startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), EXPORT_FILE); }
                } catch (Exception e) { Toast.makeText(this, isEnglish() ? "No compatible app found" : "没有可处理此文件的应用", Toast.LENGTH_LONG).show(); }
            }).show();
        } catch (Exception e) { Toast.makeText(this, isEnglish() ? "File is unavailable" : "接收文件已被移动或删除", Toast.LENGTH_LONG).show(); }
    }
    private static boolean isShare(Intent intent) { return intent != null && (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (isShare(intent)) { pendingShare = intent; deliverShare(); } }
    private void deliverShare() {
        if (!loaded || pendingShare == null) return; Intent share = pendingShare; pendingShare = null;
        commands.execute(() -> {
            try {
                JSONArray files = new JSONArray();
                if (Intent.ACTION_SEND_MULTIPLE.equals(share.getAction())) { ArrayList<Uri> uris = share.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (uris != null) for (Uri uri : uris) files.put(metadata(uri)); }
                else { Uri uri = share.getParcelableExtra(Intent.EXTRA_STREAM); if (uri != null) files.put(metadata(uri)); }
                CharSequence text = share.getCharSequenceExtra(Intent.EXTRA_TEXT);
                JSONObject payload = new JSONObject().put("files", files).put("text", text == null ? "" : text.toString());
                handler.post(() -> { if (!destroyed) web.evaluateJavascript("window.receiveShared(" + JSONObject.quote(payload.toString()) + ")", null); });
            } catch (Exception e) { handler.post(() -> Toast.makeText(this, LanDropEngine.friendlyError(e), Toast.LENGTH_LONG).show()); }
        });
    }
}
