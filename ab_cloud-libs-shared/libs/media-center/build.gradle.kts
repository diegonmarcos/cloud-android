// libs:media-center — the cloud data/domain engine behind Cloud Media Center.
//
// 107 files: the provider contract (CloudUri, ProviderType, ProviderCapability),
// the Room schema (entities, DAOs, converters), feature_node.domain, and the
// sync / decoder / metadata support they need. Everything the app's cloud
// providers stand on, and nothing that draws.
//
// The set is not a judgement call — it is the transitive dependency CLOSURE of
// the cloud engine, computed from the imports. That matters, because the first
// attempt at this split used a three-test filter (no UI imports, no upstream
// imports, lives in src/main), got 36 files, and failed: those 36 imported no
// UI directly but depended on files that did. A closure cannot have that gap.
//
// It took two things to make the closure clean:
//
//  1. The rename off com.dot.gallery. Our engine is built on the fork's domain
//     model — CloudServerConfig needs Album/Media, RemoteMediaProvider needs
//     Resource — so while those were upstream's types there was a fork boundary
//     no library could cross. They are ours now, so the boundary is gone.
//
//  2. Cutting the layering violations. Six UI files sat in the closure through
//     ONE edge: core/sandbox/IsolatedMetadataParser imported MetadataDirectory
//     and MetadataTag, two plain data classes that happened to be declared in a
//     ViewModel file, which reached core/Settings (1371 lines, 16 Compose
//     imports) and from there the whole UI layer.
//
// Closure today: 107 files, zero presentation, zero behind a build toggle.
//
// androidx.compose.runtime IS a dependency, and deliberately: @Stable and
// @Immutable are state annotations, not UI. compose.ui is not here and must not
// arrive — that is the line this module exists to hold.
//
// NOT shipped as its own APK (lib_apks.exclude): it is one app's database schema
// and provider contract, useless standalone, and it builds against
// ac_cloud-media-center's version catalog and AGP 9, neither of which lib-apks
// configures.

plugins {
    // No org.jetbrains.kotlin.android: AGP 9 supplies Kotlin itself and applying
    // it on top is a hard error — which is why the catalog has no alias for it.
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.diegonmarcos.mediacenter.enginelib"
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // State annotations only (@Stable/@Immutable, snapshot state). NOT compose.ui.
    implementation(libs.compose.runtime)

    // Room ANNOTATIONS only — no room-compiler/KSP. The @Database is declared in
    // the app and Room generates DAO implementations there, so a compiler in
    // this module would emit nothing and cost a KSP pass on every build.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Hilt annotations only — @Inject constructors and @ApplicationContext. No
    // Hilt plugin: nothing here is an @AndroidEntryPoint and no component is
    // generated in this module; the app's Hilt compiler reads these classes.
    implementation(libs.dagger.hilt)

    // Image pipeline: the cloud fetchers plug into both.
    implementation(libs.glide.compose)
    implementation(libs.sketch.compose)
}
