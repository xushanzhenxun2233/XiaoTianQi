package com.xiaotianqi;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

public final class AppSettings {

    private static final String PREFS_NAME = "xiao_app_settings";
    private static final String KEY_FONT_SCALE = "app_font_scale";
    private static final String KEY_SPEECH_RATE = "tts_speech_rate";
    private static final String KEY_PROVIDER = "weather_provider_name";

    public static final float FONT_NORMAL = 0.95f;
    public static final float FONT_LARGE = 1.15f;     // 默认更大更清晰
    public static final float FONT_XLARGE = 1.30f;
    public static final float FONT_HUGE = 1.45f;

    public static final float RATE_SLOW = 0.75f;
    public static final float RATE_NORMAL = 0.95f;
    public static final float RATE_FAST = 1.20f;

    private AppSettings() {}

    public static float getFontScale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getFloat(KEY_FONT_SCALE, FONT_LARGE);
    }

    public static void setFontScale(Context context, float scale) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_FONT_SCALE, scale)
                .apply();
    }

    public static float getSpeechRate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getFloat(KEY_SPEECH_RATE, RATE_NORMAL);
    }

    public static void setSpeechRate(Context context, float rate) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_SPEECH_RATE, rate)
                .apply();
    }

    public static String getWeatherProvider(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROVIDER, "腾讯天气（中国大陆直连）");
    }

    public static void setWeatherProvider(Context context, String provider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROVIDER, provider)
                .apply();
    }

    /**
     * 同时锁定系统“显示大小（densityDpi）”与“字体大小（fontScale）”，
     * 避免系统调到最大显示+最大字体时挤压布局导致按钮或内容消失；
     * 全 App 文字大小严格只由本软件内部设置控制。
     */
    public static Context wrapContext(Context base) {
        float appScale = getFontScale(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (DisplayMetrics.DENSITY_DEVICE_STABLE > 0) {
            config.densityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE;
        }
        config.fontScale = appScale;
        return base.createConfigurationContext(config);
    }
}
