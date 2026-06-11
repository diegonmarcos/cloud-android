# Hub is not minified (build.json release.minifyEnabled=false), so this file is
# a placeholder for parity with the gradle config. Keep the AIDL stubs + the
# ContentProvider/Service entry points if minification is ever enabled.
-keep class com.diegonmarcos.ide.IIdeService { *; }
-keep class com.diegonmarcos.ide.IIdeCallback { *; }
-keep class com.diegonmarcos.ide.IdeProvider { *; }
-keep class com.diegonmarcos.ide.IdeService { *; }
