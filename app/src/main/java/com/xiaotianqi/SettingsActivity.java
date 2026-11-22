package com.xiaotianqi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity implements TtsManager.Callback {

    private TextView btnFontNormal;
    private TextView btnFontLarge;
    private TextView btnFontXLarge;
    private TextView btnFontHuge;

    private TextView btnRateSlow;
    private TextView btnRateNormal;
    private TextView btnRateFast;
    private TextView btnTestVoice;

    private TextView tvProviderName;
    private TtsManager ttsManager;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppSettings.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFFFFFFFF);
        setContentView(R.layout.activity_settings);

        ttsManager = new TtsManager(this, this);

        TextView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        btnFontNormal = findViewById(R.id.btnFontNormal);
        btnFontLarge = findViewById(R.id.btnFontLarge);
        btnFontXLarge = findViewById(R.id.btnFontXLarge);
        btnFontHuge = findViewById(R.id.btnFontHuge);

        btnRateSlow = findViewById(R.id.btnRateSlow);
        btnRateNormal = findViewById(R.id.btnRateNormal);
        btnRateFast = findViewById(R.id.btnRateFast);
        btnTestVoice = findViewById(R.id.btnTestVoice);

        tvProviderName = findViewById(R.id.tvProviderName);
        tvProviderName.setText(AppSettings.getWeatherProvider(this));

        btnFontNormal.setOnClickListener(v -> applyFontScale(AppSettings.FONT_NORMAL));
        btnFontLarge.setOnClickListener(v -> applyFontScale(AppSettings.FONT_LARGE));
        btnFontXLarge.setOnClickListener(v -> applyFontScale(AppSettings.FONT_XLARGE));
        btnFontHuge.setOnClickListener(v -> applyFontScale(AppSettings.FONT_HUGE));

        btnRateSlow.setOnClickListener(v -> applySpeechRate(AppSettings.RATE_SLOW));
        btnRateNormal.setOnClickListener(v -> applySpeechRate(AppSettings.RATE_NORMAL));
        btnRateFast.setOnClickListener(v -> applySpeechRate(AppSettings.RATE_FAST));

        btnTestVoice.setOnClickListener(v -> {
            if (ttsManager.isInitialized() && !ttsManager.isAvailable()) {
                Toast.makeText(this, "当前设备未安装中文语音引擎", Toast.LENGTH_SHORT).show();
            } else {
                ttsManager.speak("魈天气为您播报，当前语速测试正常。");
            }
        });

        ImageView ivWechat = findViewById(R.id.ivQrWechat);
        ImageView ivDouyin = findViewById(R.id.ivQrDouyin);
        ivWechat.setOnClickListener(v -> showZoomQrDialog("微信扫码联系", R.drawable.qr_wechat));
        ivDouyin.setOnClickListener(v -> showZoomQrDialog("抖音扫码联系", R.drawable.qr_douyin));

        setupTestButtons();
        refreshSelectionStates();
    }

    private void setupTestButtons() {
        LinearLayout container = findViewById(R.id.layoutTestButtons);
        String[][] tests = new String[][] {
                {"TEST: 极端高温（38°C 红色 + 闪烁2秒）", "test_extreme_heat"},
                {"TEST: 严寒大雪（-6°C 蓝色 + 闪烁2秒）", "test_extreme_cold"},
                {"TEST: 雷暴暴雨大风（绿色 + 高对比度提醒 + 闪烁2秒）", "test_extreme_storm"},
                {"TEST: 断网状态（顶部地区置灰禁止切换）", "test_offline_mode"},
                {"TEST: 数据严重过期（引导打开流量弹窗）", "test_expired_data"},
                {"TEST: 无 TTS 引擎（引导安装语音引擎弹窗）", "test_no_tts"},
                {"TEST: 立即语音播报今日天气", "test_speak_now"},
                {"TEST: 打开系统流量/网络开关面板", "test_open_data_panel"},
                {"恢复真实天气数据", "test_reset_real"}
        };

        int padH = dp(14);
        int padV = dp(11);
        for (int i = 0; i < tests.length; i++) {
            final String label = tests[i][0];
            final String action = tests[i][1];
            boolean isReset = "test_reset_real".equals(action);

            TextView btn = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(8);
            btn.setLayoutParams(lp);
            btn.setPadding(padH, padV, padH, padV);
            btn.setText(label);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            if (isReset) {
                btn.setBackgroundColor(0xFF111111);
                btn.setTextColor(0xFFFFFFFF);
                btn.setTypeface(null, Typeface.BOLD);
            } else {
                btn.setBackgroundColor(0xFFF3F3F3);
                btn.setTextColor(0xFF111111);
            }
            btn.setOnClickListener(v -> triggerTestInMain(action));
            container.addView(btn);
        }
    }

    private void triggerTestInMain(String testAction) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("xiao_test_action", testAction);
        startActivity(intent);
        finish();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void applyFontScale(float scale) {
        if (Math.abs(AppSettings.getFontScale(this) - scale) < 0.01f) return;
        AppSettings.setFontScale(this, scale);
        recreate();
    }

    private void applySpeechRate(float rate) {
        AppSettings.setSpeechRate(this, rate);
        ttsManager.setSpeechRate(rate);
        refreshSelectionStates();
        Toast.makeText(this, "播报语速已更新", Toast.LENGTH_SHORT).show();
    }

    private void refreshSelectionStates() {
        float curFont = AppSettings.getFontScale(this);
        styleOption(btnFontNormal, Math.abs(curFont - AppSettings.FONT_NORMAL) < 0.05f);
        styleOption(btnFontLarge, Math.abs(curFont - AppSettings.FONT_LARGE) < 0.05f);
        styleOption(btnFontXLarge, Math.abs(curFont - AppSettings.FONT_XLARGE) < 0.05f);
        styleOption(btnFontHuge, Math.abs(curFont - AppSettings.FONT_HUGE) < 0.05f);

        float curRate = AppSettings.getSpeechRate(this);
        styleOption(btnRateSlow, Math.abs(curRate - AppSettings.RATE_SLOW) < 0.05f);
        styleOption(btnRateNormal, Math.abs(curRate - AppSettings.RATE_NORMAL) < 0.05f);
        styleOption(btnRateFast, Math.abs(curRate - AppSettings.RATE_FAST) < 0.05f);
    }

    private void styleOption(TextView tv, boolean selected) {
        if (selected) {
            tv.setBackgroundColor(0xFF111111);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            tv.setBackgroundColor(0xFFF3F3F3);
            tv.setTextColor(0xFF222222);
            tv.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void showZoomQrDialog(String title, int drawableRes) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        iv.setPadding(pad, pad, pad, pad);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setBackgroundColor(0xFFFFFFFF);
        wrap.addView(iv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    @Override
    public void onTtsReady() {}

    @Override
    public void onTtsUnavailable(String reason) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ttsManager != null) {
            ttsManager.shutdown();
        }
    }
}
