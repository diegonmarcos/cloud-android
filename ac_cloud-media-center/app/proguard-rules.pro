# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

-keep class com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.** { *; }
# The editor's models were moved OUT of the package above into
# domain.model.editor by the 2026-08-30 layering split (0d1bb618a, fd2f13580),
# which silently dropped them out of the keep rule that had covered them since
# upstream. EditorDestination's subclasses are Navigation-Compose type-safe
# routes, so their generated serializers are resolved when the editor graph is
# built — the same class of runtime-only failure bc507e557 fixed by restoring
# @Keep/@Serializable on VariableFilterTypes. Restores the pre-move parity for
# the whole package rather than annotating them one at a time.
-keep class com.diegonmarcos.mediacenter.feature_node.domain.model.editor.** { *; }
-keep class com.drew.** { *; }
-keep class java.io.** { *; }
-keep class com.adobe.** { *; }
-keep class ai.onnxruntime.** { *; }

-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options

# Keep custom Glide decoders and model loaders (HEIF/JXL/Encrypted)
-keep class com.diegonmarcos.mediacenter.core.decoder.glide.** { *; }
-keep class com.radzivon.bartoshyk.avif.** { *; }
-keep class com.awxkee.jxlcoder.** { *; }
-dontwarn com.radzivon.bartoshyk.avif.**
-dontwarn com.awxkee.jxlcoder.**

# Cloud provider models (Retrofit DTOs, Room entities, serialization)
-keep class com.diegonmarcos.mediacenter.cloud.** { *; }
-keep class com.diegonmarcos.mediacenter.cloud.data.entity.** { *; }
-keep class com.diegonmarcos.mediacenter.cloud.core.** { *; }
-keep class com.diegonmarcos.mediacenter.cloud.image.** { *; }

-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid