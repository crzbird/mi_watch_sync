package com.swipesync.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：状态展示 + 服务开关 + 手势测试 + 设置。
 * 纯系统控件，零第三方 UI 依赖。
 */
public class MainActivity extends Activity implements StatusBus.Listener {

    private TextView mStatusText;
    private TextView mLabelDist;
    private Button mBtnA11y;
    private Button mBtnService;
    private SeekBar mSeekDist;
    private RadioGroup mRadioSpeed;
    private Switch mSwitchInvert;
    private Switch mSwitchAutoStart;

    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(GestureService.PREFS, Context.MODE_PRIVATE);

        mStatusText = findViewById(R.id.statusText);
        mLabelDist = findViewById(R.id.labelDist);
        mBtnA11y = findViewById(R.id.btnA11y);
        mBtnService = findViewById(R.id.btnService);
        mSeekDist = findViewById(R.id.seekDist);
        mRadioSpeed = findViewById(R.id.radioSpeed);
        mSwitchInvert = findViewById(R.id.switchInvert);
        mSwitchAutoStart = findViewById(R.id.switchAutoStart);

        // 设置项初始化（seek 10~60%，progress 0~50）
        int distPct = mPrefs.getInt(GestureService.KEY_DIST_PCT, 30);
        mSeekDist.setProgress(distPct - 10);
        mLabelDist.setText("滑动距离：" + distPct + "%");
        int speed = mPrefs.getInt(GestureService.KEY_SPEED, 1);
        mRadioSpeed.check(speed == 0 ? R.id.speedFast : (speed == 2 ? R.id.speedSlow : R.id.speedNormal));
        mSwitchInvert.setChecked(mPrefs.getBoolean(GestureService.KEY_INVERT, false));
        mSwitchAutoStart.setChecked(mPrefs.getBoolean(GestureService.KEY_AUTO_START, true));

        mSeekDist.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = progress + 10;
                mLabelDist.setText("滑动距离：" + pct + "%");
                if (fromUser) {
                    mPrefs.edit().putInt(GestureService.KEY_DIST_PCT, pct).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mRadioSpeed.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int speed = checkedId == R.id.speedFast ? 0 : (checkedId == R.id.speedSlow ? 2 : 1);
                mPrefs.edit().putInt(GestureService.KEY_SPEED, speed).apply();
            }
        });

        mSwitchInvert.setOnCheckedChangeListener((buttonView, isChecked) ->
                mPrefs.edit().putBoolean(GestureService.KEY_INVERT, isChecked).apply());

        mSwitchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) ->
                mPrefs.edit().putBoolean(GestureService.KEY_AUTO_START, isChecked).apply());

        mBtnA11y.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "请在列表中开启「滑动同步手势注入」", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        mBtnService.setOnClickListener(v -> {
            if (StatusBus.serviceRunning) {
                SyncService.stop(this);
            } else {
                SyncService.start(this);
            }
            // 状态由 StatusBus 回调刷新
            mStatusText.postDelayed(this::refresh, 400);
        });

        findViewById(R.id.btnTestUp).setOnClickListener(v -> test(SwipeEvent.UP));
        findViewById(R.id.btnTestDown).setOnClickListener(v -> test(SwipeEvent.DOWN));
        findViewById(R.id.btnTestLeft).setOnClickListener(v -> test(SwipeEvent.LEFT));
        findViewById(R.id.btnTestRight).setOnClickListener(v -> test(SwipeEvent.RIGHT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.setListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        StatusBus.setListener(null);
        super.onPause();
    }

    @Override
    public void onStatusChanged() {
        refresh();
    }

    private void test(String dir) {
        if (!GestureService.isEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            return;
        }
        GestureService.dispatchSwipe(new SwipeEvent(dir, false, 0));
        Toast.makeText(this, "已测试 " + dir + " 滑动", Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务：").append(GestureService.isEnabled() ? "已开启" : "未开启").append('\n');
        sb.append("同步服务：").append(StatusBus.serviceRunning ? "运行中" : "未运行").append('\n');
        sb.append("运动健康通道：").append(StatusBus.healthConnected ? "已连接" : "未连接").append('\n');
        sb.append("手表节点：").append(StatusBus.nodeInfo).append('\n');
        sb.append("最近事件：").append(StatusBus.lastEvent).append('\n');
        sb.append("累计同步：").append(StatusBus.eventCount).append(" 次");
        if (StatusBus.lastError != null && !StatusBus.lastError.isEmpty()) {
            sb.append('\n').append("⚠ ").append(StatusBus.lastError);
        }
        mStatusText.setText(sb.toString());

        mBtnService.setText(StatusBus.serviceRunning ? "停止同步服务" : "启动同步服务");
    }
}
