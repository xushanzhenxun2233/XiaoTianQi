package com.xiaotianqi;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.speech.tts.TextToSpeech;

import java.util.List;
import java.util.Locale;

public class TtsManager implements TextToSpeech.OnInitListener {

    public interface Callback {
        void onTtsReady();
        void onTtsUnavailable(String reason);
    }

    private final Context context;
    private final Callback callback;
    private TextToSpeech tts;
    private boolean initialized = false;
    private boolean available = false;
    private String pendingText = null;
    private String unavailableReason = null;

    public TtsManager(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        try {
            this.tts = new TextToSpeech(this.context, this);
        } catch (Exception e) {
            markUnavailable("系统未检测到可用的语音朗读（TTS）引擎。");
        }
    }

    @Override
    public void onInit(int status) {
        initialized = true;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            markUnavailable("系统未安装语音朗读（TTS）引擎，或引擎初始化失败。");
            return;
        }

        List<TextToSpeech.EngineInfo> engines = tts.getEngines();
        if (engines == null || engines.isEmpty()) {
            markUnavailable("当前设备尚未安装任何 TTS 语音引擎。");
            return;
        }

        int langResult = tts.setLanguage(Locale.CHINA);
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            langResult = tts.setLanguage(Locale.CHINESE);
        }

        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            markUnavailable("当前 TTS 引擎缺少中文语音包，无法朗读天气。");
            return;
        }

        tts.setSpeechRate(AppSettings.getSpeechRate(context));
        tts.setPitch(1.0f);
        available = true;

        if (callback != null) {
            callback.onTtsReady();
        }
        if (pendingText != null) {
            String toSpeak = pendingText;
            pendingText = null;
            speakNow(toSpeak);
        }
    }

    private void markUnavailable(String reason) {
        available = false;
        unavailableReason = reason;
        pendingText = null;
        if (callback != null) {
            callback.onTtsUnavailable(reason);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getUnavailableReason() {
        return unavailableReason != null ? unavailableReason : "未检测到可用的中文 TTS 语音引擎。";
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!initialized) {
            pendingText = text;
            return;
        }
        if (!available) {
            if (callback != null) {
                callback.onTtsUnavailable(getUnavailableReason());
            }
            return;
        }
        speakNow(text);
    }

    public void setSpeechRate(float rate) {
        if (tts != null && available) {
            try {
                tts.setSpeechRate(rate);
            } catch (Exception ignored) {
            }
        }
    }

    private void speakNow(String text) {
        if (tts == null || !available) return;
        try {
            tts.setSpeechRate(AppSettings.getSpeechRate(context));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xiao_weather_tts");
        } catch (Exception e) {
            markUnavailable("语音朗读调用失败，请检查系统 TTS 引擎设置。");
        }
    }

    public static boolean openInstallTtsData(Context ctx) {
        try {
            Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return openTtsSettings(ctx);
        }
    }

    public static boolean openTtsSettings(Context ctx) {
        try {
            Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    public void stop() {
        pendingText = null;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
    }
}
