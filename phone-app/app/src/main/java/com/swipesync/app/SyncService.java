package com.swipesync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import com.xiaomi.xms.wearable.service.ServiceApi;
import com.xiaomi.xms.wearable.tasks.OnFailureListener;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;

import java.util.List;

/**
 * 前台服务：通过 XMS Wearable SDK（小米穿戴第三方能力开放接口）
 * 从"小米运动健康"通道接收手表端 interconnect 消息，转发给无障碍服务执行手势。
 *
 * 设计要点：
 * - 常驻前台服务（低优先级通知），保证后台存活；
 * - 每 20 秒检查一次手表节点与消息监听，自动重连/重注册；
 * - 不做任何蓝牙直连，不干扰小米运动健康与手表的连接。
 */
public class SyncService extends Service {

    private static final String CHANNEL_ID = "swipesync_sync";
    private static final int NOTIF_ID = 1;
    private static final long REFRESH_INTERVAL_MS = 20_000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private NodeApi mNodeApi;
    private AuthApi mAuthApi;
    private MessageApi mMessageApi;
    private ServiceApi mServiceApi;

    private volatile String mNodeId;
    private volatile boolean mListenerRegistered = false;

    private final OnMessageReceivedListener mMessageListener = new OnMessageReceivedListener() {
        @Override
        public void onMessageReceived(@NonNull String nodeId, @NonNull byte[] message) {
            handleWatchMessage(message);
        }
    };

    private final OnServiceConnectionListener mServiceListener = new OnServiceConnectionListener() {
        @Override
        public void onServiceConnected() {
            StatusBus.healthConnected = true;
            StatusBus.lastError = "";
            StatusBus.notifyChanged();
            updateNotification("已连接小米运动健康");
            refresh();
        }

        @Override
        public void onServiceDisconnected() {
            StatusBus.healthConnected = false;
            mListenerRegistered = false;
            StatusBus.notifyChanged();
            updateNotification("与小米运动健康断开");
        }
    };

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    public static void start(Context c) {
        Intent i = new Intent(c, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SyncService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动…"));
        StatusBus.serviceRunning = true;

        mNodeApi = Wearable.getNodeApi(this);
        mAuthApi = Wearable.getAuthApi(this);
        mMessageApi = Wearable.getMessageApi(this);
        mServiceApi = Wearable.getServiceApi(this);
        mServiceApi.registerServiceConnectionListener(mServiceListener);

        StatusBus.notifyChanged();
        mHandler.post(mRefreshRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        try {
            if (mListenerRegistered && mNodeId != null) {
                mMessageApi.removeListener(mNodeId);
            }
        } catch (Exception ignored) {
        }
        try {
            mServiceApi.unregisterServiceConnectionListener(mServiceListener);
        } catch (Exception ignored) {
        }
        mListenerRegistered = false;
        StatusBus.serviceRunning = false;
        StatusBus.notifyChanged();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------------- SDK 逻辑 ---------------- */

    private void refresh() {
        mNodeApi.getConnectedNodes()
                .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                    @Override
                    public void onSuccess(List<Node> nodes) {
                        if (nodes == null || nodes.isEmpty()) {
                            StatusBus.nodeFound = false;
                            StatusBus.nodeInfo = "未发现已连接手表";
                            mListenerRegistered = false;
                            StatusBus.notifyChanged();
                            updateNotification("等待手表连接…");
                            return;
                        }
                        Node node = nodes.get(0);
                        StatusBus.nodeFound = true;
                        StatusBus.nodeInfo = String.valueOf(node);
                        StatusBus.notifyChanged();
                        ensureListener(node.id);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        StatusBus.logError("查询手表失败: " + e.getMessage());
                    }
                });
    }

    private void ensureListener(final String nodeId) {
        if (mListenerRegistered && nodeId.equals(mNodeId)) {
            return;
        }
        mNodeId = nodeId;
        // 先确保 DEVICE_MANAGER 授权（首次调用默认授予）
        mAuthApi.requestPermission(nodeId, Permission.DEVICE_MANAGER)
                .addOnSuccessListener(new OnSuccessListener<Permission[]>() {
                    @Override
                    public void onSuccess(Permission[] permissions) {
                        registerListener(nodeId);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // 即使授权查询失败也尝试注册（部分固件无需显式授权）
                        registerListener(nodeId);
                    }
                });
    }

    private void registerListener(String nodeId) {
        try {
            if (mListenerRegistered && nodeId.equals(mNodeId)) {
                return;
            }
            if (mListenerRegistered) {
                try {
                    mMessageApi.removeListener(mNodeId);
                } catch (Exception ignored) {
                }
                mListenerRegistered = false;
            }
        } catch (Exception ignored) {
        }

        mMessageApi.addListener(nodeId, mMessageListener)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        mListenerRegistered = true;
                        StatusBus.lastError = "";
                        StatusBus.notifyChanged();
                        updateNotification("正在同步手表滑动");
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        mListenerRegistered = false;
                        StatusBus.logError("消息监听注册失败: " + e.getMessage());
                    }
                });
    }

    private void handleWatchMessage(byte[] message) {
        SwipeEvent ev = MsgParser.parse(message);
        if (ev == null) {
            StatusBus.logError("收到无法识别的消息（已忽略）");
            return;
        }
        GestureService.dispatchSwipe(ev);
        String arrow;
        switch (ev.dir) {
            case SwipeEvent.UP: arrow = "↑"; break;
            case SwipeEvent.DOWN: arrow = "↓"; break;
            case SwipeEvent.LEFT: arrow = "←"; break;
            case SwipeEvent.RIGHT: arrow = "→"; break;
            default: arrow = "?";
        }
        StatusBus.logEvent(arrow + (ev.continuous ? " 连续" : " 单次") + " · #" + ev.seq);
    }

    /* ---------------- 通知 ---------------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String content) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(content)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(content));
        }
    }
}
