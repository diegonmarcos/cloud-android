package com.diegonmarcos.cloudkeyboardlibs;
oneway interface IVoiceCallback {
    void onPartial(String text);
    void onFinal(String text);
    void onError(String message);
}
