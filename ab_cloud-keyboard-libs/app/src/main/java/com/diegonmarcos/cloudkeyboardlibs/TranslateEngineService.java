package com.diegonmarcos.cloudkeyboardlibs;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TranslateEngineService extends Service {

    private final ITranslateEngine.Stub mBinder = new ITranslateEngine.Stub() {

        @Override
        public String[] translate(String text, String targetTag) {
            try {
                return com.diegonmarcos.superapp.translate.TranslateEngine
                        .translateBlocking(TranslateEngineService.this, text, targetTag);
            } catch (Throwable t) {
                return new String[]{"und", ""};
            }
        }

        @Override
        public java.util.List<String> supportedLanguages() {
            try {
                return com.diegonmarcos.superapp.translate.TranslateEngine
                        .supportedLanguageTags();
            } catch (Throwable t) {
                return java.util.Collections.emptyList();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Best-effort prefetch of common language models at startup.
        try {
            com.diegonmarcos.superapp.translate.TranslateEngine
                    .prefetchDefaults(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
