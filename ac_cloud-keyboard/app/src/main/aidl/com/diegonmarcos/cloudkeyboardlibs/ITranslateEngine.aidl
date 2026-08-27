package com.diegonmarcos.cloudkeyboardlibs;
interface ITranslateEngine {
    String[] translate(String text, String targetTag);
    List<String> supportedLanguages();
}
