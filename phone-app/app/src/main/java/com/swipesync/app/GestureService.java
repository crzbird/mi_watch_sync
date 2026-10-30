package com.swipesync.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：在手机屏幕上注入滑动手势。
 * 仅执行手势，不读取任何屏幕内容（canRetrieveWindowContent=false）。
 */
public class GestureService extends AccessibilityService {

    private static volatile GestureService sInstance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // 设置键
    public static final String PREFS = "swipesync";
    public static final String KEY_DIST_PCT = "dist_pct";       // 滑动距离：屏幕短边百分比
    public static final String KEY_SPEED = "speed";             // 0=快 1=标准 2=慢
    public static final String KEY_INVERT = "invert";           // 方向反转
    public static final String KEY_AUTO_START = "auto_start";   // 开机自启

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        StatusBus.notifyChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不处理任何无障碍事件，仅用本服务执行手势
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        StatusBus.notifyChanged();
        super.onDestroy();
    }

    public static boolean isEnabled() {
        return sInstance != null;
    }

    /** 供服务/界面调用：执行一次滑动（主线程调度） */
    public static void dispatchSwipe(final SwipeEvent ev) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                GestureService s = sInstance;
                if (s == null) {
                    StatusBus.logError("无障碍服务未开启，手势已丢弃");
                    return;
                }
                s.performSwipe(ev);
            }
        });
    }

    private void performSwipe(SwipeEvent ev) {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.getCurrentWindowMetrics().getBounds();
            metrics = getResources().getDisplayMetrics();
        } else {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        if (w <= 0 || h <= 0) {
            return;
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int distPct = p.getInt(KEY_DIST_PCT, 30);
        int speed = p.getInt(KEY_SPEED, 1);
        boolean invert = p.getBoolean(KEY_INVERT, false);

        int base = Math.min(w, h);
        int dist = Math.round(base * (distPct / 100f));
        if (ev.continuous) {
            dist = Math.round(dist * 0.55f); // 连续模式单步稍短，手感更跟手
        }
        dist = Math.max(dist, base / 12);

        String dir = ev.dir;
        if (invert) {
            if (SwipeEvent.UP.equals(dir)) dir = SwipeEvent.DOWN;
            else if (SwipeEvent.DOWN.equals(dir)) dir = SwipeEvent.UP;
            else if (SwipeEvent.LEFT.equals(dir)) dir = SwipeEvent.RIGHT;
            else if (SwipeEvent.RIGHT.equals(dir)) dir = SwipeEvent.LEFT;
        }

        // 从屏幕中心出发向目标方向滑动（避开边缘手势区）
        float cx = w / 2f;
        float cy = h / 2f;
        Path path = new Path();
        switch (dir) {
            case SwipeEvent.UP:
                path.moveTo(cx, cy + dist / 2f);
                path.lineTo(cx, cy - dist / 2f);
                break;
            case SwipeEvent.DOWN:
                path.moveTo(cx, cy - dist / 2f);
                path.lineTo(cx, cy + dist / 2f);
                break;
            case SwipeEvent.LEFT:
                path.moveTo(cx + dist / 2f, cy);
                path.lineTo(cx - dist / 2f, cy);
                break;
            case SwipeEvent.RIGHT:
                path.moveTo(cx - dist / 2f, cy);
                path.lineTo(cx + dist / 2f, cy);
                break;
            default:
                return;
        }

        long duration = durationFor(speed, ev.continuous);
        GestureDescription.Builder builder = new GestureDescription();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));

        boolean ok = dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                // 静默成功，计数在调用方
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                StatusBus.logError("手势被系统取消");
            }
        }, null);

        if (!ok) {
            StatusBus.logError("手势派发失败（可能正在执行其它手势）");
        }
    }

    private static long durationFor(int speed, boolean continuous) {
        // speed: 0=快 1=标准 2=慢
        long base;
        if (continuous) {
            base = speed == 0 ? 60 : (speed == 2 ? 130 : 90);
        } else {
            base = speed == 0 ? 130 : (speed == 2 ? 320 : 200);
        }
        return base;
    }
}
