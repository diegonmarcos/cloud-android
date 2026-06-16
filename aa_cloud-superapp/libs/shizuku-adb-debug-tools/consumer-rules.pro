# No consumer ProGuard rules needed — the lib exposes plain Kotlin
# objects + an AIDL stub that Shizuku instantiates by reflection (the
# stub class name is referenced via ComponentName, which R8 keeps because
# it's reachable from ShizukuAdb). If minification ever strips
# ShellUserService, add: -keep class com.diegonmarcos.superapp.adbdebug.ShellUserService { *; }
