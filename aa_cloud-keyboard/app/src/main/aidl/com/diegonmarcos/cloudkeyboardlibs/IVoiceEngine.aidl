package com.diegonmarcos.cloudkeyboardlibs;
import com.diegonmarcos.cloudkeyboardlibs.IVoiceCallback;
interface IVoiceEngine {
    void start(IVoiceCallback cb);
    void feed(in byte[] pcm, int len);
    void stop();
    void setLanguageTag(String tag);
}
