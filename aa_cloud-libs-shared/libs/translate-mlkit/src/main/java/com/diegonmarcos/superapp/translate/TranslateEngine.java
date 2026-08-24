package com.diegonmarcos.superapp.translate;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TranslateEngine {
    // Returns {detectedLanguageTag, translatedText}. BLOCKING — call off the main thread.
    public static String[] translateBlocking(Context context, String text, String targetTag) throws IOException {
        if (text == null || text.trim().isEmpty())
            return new String[]{"und", text == null ? "" : text};
        String detected;
        LanguageIdentifier identifier = LanguageIdentification.getClient();
        try {
            detected = Tasks.await(identifier.identifyLanguage(text));
        } catch (Throwable ex) {
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException(ex);
        } finally {
            identifier.close();
        }
        String source = TranslateLanguage.fromLanguageTag(detected);
        String target = TranslateLanguage.fromLanguageTag(targetTag);
        if (target == null) throw new IOException("Unsupported target language: " + targetTag);
        if (source == null) throw new IOException("Could not detect source language");
        if (source.equals(target)) return new String[]{detected, text};
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(target).build();
        Translator translator = Translation.getClient(options);
        try {
            Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
            String result = Tasks.await(translator.translate(text));
            return new String[]{detected, result};
        } catch (Throwable ex) {
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException(ex);
        } finally {
            translator.close();
        }
    }

    public static List<String> supportedLanguageTags() {
        return new ArrayList<>(TranslateLanguage.getAllLanguages());
    }

    // Best-effort background pre-download of en/pt/es/de models (Wi-Fi only).
    public static void prefetchDefaults(Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    RemoteModelManager manager = RemoteModelManager.getInstance();
                    DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
                    String[] defaults = {TranslateLanguage.ENGLISH, TranslateLanguage.PORTUGUESE, TranslateLanguage.SPANISH, TranslateLanguage.GERMAN};
                    for (String tag : defaults) {
                        try {
                            TranslateRemoteModel model = new TranslateRemoteModel.Builder(tag).build();
                            Tasks.await(manager.download(model, conditions));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }
}
