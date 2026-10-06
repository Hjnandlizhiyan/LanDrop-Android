package com.deepseeklover.landrop;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public final class TransferService extends Service {
    static volatile TransferService instance;
    volatile LanDropEngine engine;
    static volatile String startupError;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private String lastNotification = "";
    private volatile boolean closing;
    private final Runnable notificationTick = new Runnable() {
        public void run() {
            if (closing) return;
            try {
                if (engine != null) {
                    boolean active = engine.hasActiveTransfers();
                    if (active && !wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
                    else if (!active && wakeLock.isHeld()) wakeLock.release();
                    boolean en = engine.language().equals("en");
                    String text = active ? (en ? "Transferring · keep both devices connected" : "正在传输 · 请保持双方网络连接")
                            : engine.isEnabled() ? (en ? "Ready to receive · tap to open" : "等待接收 · 点按打开 LanDrop")
                            : (en ? "LAN transfers are paused" : "局域网传输已暂停");
                    if (!text.equals(lastNotification)) {
                        getSystemService(NotificationManager.class).notify(101, notification(text)); lastNotification = text;
                    }
                }
            } catch (Exception e) { Log.w("LanDrop", "Notification update failed", e); }
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate() {
        super.onCreate(); instance = this; startupError = null;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("transfers", "LanDrop · 局域网传输", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示接收服务和传输状态"); getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification initial = notification("正在启动局域网传输…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(101, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(101, initial);
        wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanDrop:transfer");
        wakeLock.setReferenceCounted(false);
        lifecycle.execute(() -> {
            try {
                File received = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) == null ? getFilesDir() : getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LanDrop");
                LanDropEngine.Storage storage = new LanDropEngine.DiskStorage(received);
                String tree = getSharedPreferences("android", MODE_PRIVATE).getString("receiveTree", ""); String warning = "";
                if (!tree.isEmpty()) {
                    try { TreeStorage selected = new TreeStorage(getContentResolver(), Uri.parse(tree)); selected.cleanupAbandoned(); storage = selected; }
                    catch (Exception e) { warning = "接收文件夹权限已失效，暂存到应用文件夹；请重新选择接收位置"; }
                }
                LanDropEngine created = new LanDropEngine(new File(getFilesDir(), "state.json"), storage, filename -> getAssets().open(filename), Build.MODEL);
                created.setWarning(warning);
                created.start(45878);
                if (closing) { created.close(); return; }
                engine = created; handler.post(notificationTick);
            } catch (Exception e) {
                startupError = LanDropEngine.friendlyError(e); Log.e("LanDrop", "Service startup failed", e); handler.post(this::stopSelf);
            }
        });
    }
    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TransferService.class).setAction("stop");
        PendingIntent action = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean en = engine != null && engine.language().equals("en");
        return new Notification.Builder(this, "transfers").setSmallIcon(R.drawable.ic_transfer).setContentTitle("LanDrop · 局域传送")
                .setContentText(text).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, en ? "Stop transfers" : "停止传输", action).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        closing = true; handler.removeCallbacksAndMessages(null); instance = null;
        LanDropEngine previous = engine; engine = null;
        lifecycle.execute(() -> { if (previous != null) try { previous.close(); } catch (Exception e) { Log.w("LanDrop", "Shutdown failed", e); } });
        lifecycle.shutdown(); if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) { stopSelf(); }
}
