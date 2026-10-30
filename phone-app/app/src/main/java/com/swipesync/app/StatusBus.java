package com.swipesync.app;

import android.os.Handler;
import android.os.Looper;

/**
 * 极简状态总线：服务层 → UI 层。单监听者，主线程回调。
 */
public final class StatusBus {

    public interface Listener {
        void onStatusChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Listener sListener;

    // 状态字段（volatile 足够，读写都是单值）
    public static volatile boolean serviceRunning = false;
    public static volatile boolean healthConnected = false; // 小米运动健康服务连接
    public static volatile boolean nodeFound = false;
    public static volatile String nodeInfo = "未发现手表";
    public static volatile String lastEvent = "无";
    public static volatile long eventCount = 0;
    public static volatile String lastError = "";

    private StatusBus() {
    }

    public static void setListener(Listener l) {
        sListener = l;
    }

    public static void notifyChanged() {
        final Listener l = sListener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    l.onStatusChanged();
                }
            });
        }
    }

    public static void logEvent(String text) {
        lastEvent = text;
        eventCount++;
        notifyChanged();
    }

    public static void logError(String text) {
        lastError = text;
        notifyChanged();
    }
}
