# libs:updater consumer ProGuard rules. WorkManager instantiates the
# CoroutineWorker by reflection — keep its name + constructor.
-keep class com.diegonmarcos.cloudnav.updater.UpdateWorker { *; }
-keep class com.diegonmarcos.cloudnav.updater.PackageInstallerReceiver { *; }
