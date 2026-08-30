// libs:media-center — the cloud storage engine behind Cloud Media Center.
//
// The app is a fork of ReFra (com.dot.gallery, Apache-2.0), so the split runs
// along OUR code, not upstream's: everything here is from com.dot.gallery.cloud,
// the provider layer added on top of the fork. Upstream's own packages
// (feature_node/**, core/**) stay in the app, because fragmenting them across a
// module boundary would fight every future rebase for no gain.
//
// What qualifies for this module, measured rather than guessed:
//   - no Compose/UI imports at all
//   - no reference to com.dot.gallery.feature_node or com.dot.gallery.core
//   - lives in src/main, i.e. compiled unconditionally
// That last one matters: app/build.gradle.kts toggles most provider source sets
// on app.properties flags (src/immich vs src/noimmich, and so on). Files behind
// a toggle cannot move into an unconditional library without turning a build
// flag into a hard dependency, so they stay put. 36 files meet all three tests
// — the provider abstraction, the Room schema, and the Glide/media plumbing.
//
// This is deliberately the stable half: entities and DAOs are schema, which
// changes only with a migration, and ProviderType/ProviderCapability/CloudUri
// are the contract every provider implements. The churn lives in cloud/ui and
// upstream's presentation layer, which is exactly what stays in the app.
//
// NOT shipped as its own APK — see lib_apks.exclude in
// ab_cloud-libs-shared/lib-apks/build.json, alongside media-center's other
// app-specific modules (cropper, gesture, panoramaviewer, scrollbar).

plugins {
    // NO org.jetbrains.kotlin.android here: this app is on AGP 9.2.1, which
    // supplies Kotlin itself and registers the `kotlin` extension. Applying the
    // Kotlin Android plugin on top of AGP 9 is a hard error, which is why the
    // version catalog has no alias for it and the root build.gradle.kts never
    // declares one either.
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.dot.gallery.cloud"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    // Room ANNOTATIONS only, deliberately no room-compiler/KSP here. The
    // @Database lives in the app, and Room generates DAO implementations in the
    // module that declares it — a compiler in this module would emit nothing
    // and cost a KSP pass on every build.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Hilt annotations only, for @Inject constructors and the
    // @ApplicationContext qualifier. No hilt gradle plugin: nothing here is an
    // @AndroidEntryPoint and no component is generated in this module — the
    // app's Hilt compiler reads these classes when it builds the graph.
    implementation(libs.dagger.hilt)

    // CloudGlideModelLoader / CloudMediaFetcher register a Glide model loader.
    // glide-compose is the alias the app uses; it brings glide core with it.
    implementation(libs.glide.compose)
}
