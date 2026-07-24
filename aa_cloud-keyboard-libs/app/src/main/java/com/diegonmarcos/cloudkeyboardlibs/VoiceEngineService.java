package com.diegonmarcos.cloudkeyboardlibs;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.diegonmarcos.superapp.voice.LocalVoiceEngineClient;

public class VoiceEngineService extends Service {

    private LocalVoiceEngineClient mEngine;

    private final IVoiceEngine.Stub mBinder = new IVoiceEngine.Stub() {

        @Override
        public void start(IVoiceCallback cb) {
            try {
                mEngine.start(
                    text -> { try { cb.onPartial(text); } catch (Throwable ignored) {} },
                    text -> { try { cb.onFinal(text);   } catch (Throwable ignored) {} },
                    msg  -> { try { cb.onError(msg);    } catch (Throwable ignored) {} }
                );
            } catch (Throwable t) {
                try { cb.onError(t.getMessage() != null ? t.getMessage() : "start failed"); }
                catch (Throwable ignored) {}
            }
        }

        @Override
        public void feed(byte[] pcm, int len) {
            try { mEngine.feed(pcm, len); } catch (Throwable ignored) {}
        }

        @Override
        public void stop() {
            try { mEngine.stop(); } catch (Throwable ignored) {}
        }

        @Override
        public void setLanguageTag(String tag) {
            try { mEngine.setLanguageTag(tag); } catch (Throwable ignored) {}
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mEngine = new LocalVoiceEngineClient(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
