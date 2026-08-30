# Cloud SuperApp — R8 rules for the SHIPPED build.
#
# The shipped artifact is the debug variant (build.json::release.artifact._doc:
# "the daily-driver build ... it is THE app for this stack"), chosen so crashes
# give real stacktraces. So R8 runs here in SHRINK-ONLY mode:
#
#   -dontobfuscate  keeps every class/method name, which is the whole reason
#                   debug ships. Stacktraces stay readable with no mapping
#                   file, and the entire class of "worked in debug, broke in
#                   release because a name changed" bugs cannot happen.
#
# What is left is dead-code elimination, which is where the size is: the app
# linked ~20MB of dex, most of it unreachable library code.
-dontobfuscate

# Keep source line numbers - the point of shipping a debuggable build.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# ── Reflectively constructed by the framework ────────────────────────────────
# Fragments are looked up by class name on state restore; Views are inflated
# from XML by name with the (Context, AttributeSet) constructor. R8 cannot see
# either call, so both would otherwise be shrunk away.
-keep public class * extends androidx.fragment.app.Fragment { public <init>(...); }
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# WorkManager instantiates Workers by name from its database - including after
# a reboot, long after the code that enqueued them ran.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# ── AIDL across the constellation ────────────────────────────────────────────
# Binder stubs are resolved by interface descriptor STRING, not by reference,
# so asInterface() on a shrunk Stub returns a proxy for nothing. Covers
# libs:net's INetBackend and libs:shizuku-adb-debug-tools' IShellService.
-keep class * implements android.os.IInterface { *; }
-keep class **.*$Stub { *; }
-keep class **.*$Stub$Proxy { *; }

# ── Shizuku ──────────────────────────────────────────────────────────────────
# The provider and the UserService are both found by name at runtime.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ── WireGuard ────────────────────────────────────────────────────────────────
# Config/Key parsing is data the app hands across a binder as text; the JNI
# entry points in the engine APK are matched by name from native code.
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**

# ── kotlinx.serialization ────────────────────────────────────────────────────
# Generated serializers are reached through a companion the compiler emits;
# the library ships consumer rules but they do not cover our @Serializable
# classes' Companion lookups in every configuration.
-keepclassmembers class ** {
    public static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# ── Conscrypt / OkHttp-style optional providers ──────────────────────────────
# Referenced only when present; R8 warns about the absent half.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Health Connect records ───────────────────────────────────────────────────
# Record types are matched by class against the platform's own permission
# strings; shrinking one silently drops that metric rather than failing.
-keep class androidx.health.connect.client.records.** { *; }
-dontwarn androidx.health.**
