# Consumer ProGuard rules for libs:core — merged into every app that links it.
#
# This file is REQUIRED to exist because build.gradle declares
# `consumerProguardFiles 'consumer-rules.pro'`. It was declared without ever
# being created: AGP 8 ignored the dangling reference, AGP 9 fails the build on
# it, so the first AGP 9 consumer to link libs:core (media-center) went red on
# :libs:core:mergeReleaseConsumerProguardFiles while the AGP 8 apps stayed green.
# An empty-but-present file would fix the build; the rule below is here because
# it is separately needed.

# Telemetry.overrideEndpoint reads TELEMETRY_INGEST_URL off the CONSUMING app's
# BuildConfig reflectively — libs:core has no compile-time visibility of a class
# that is generated per app. R8 has no way to see that read, so in a minified
# release build the field can be shrunk away, the reflective lookup throws, and
# runCatching falls back to the DERIVED default endpoint.
#
# That failure is silent and it inverts an opt-out: an app sets
# TELEMETRY_INGEST_URL to "" precisely to disable telemetry, and losing the field
# turns it back on against the app's declared intent. Keeping the field is what
# makes both the override and the opt-out mean anything under minification.
-keepclassmembers class **.BuildConfig {
    public static java.lang.String TELEMETRY_INGEST_URL;
}
