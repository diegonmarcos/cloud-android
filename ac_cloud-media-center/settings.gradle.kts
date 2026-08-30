pluginManagement {
    includeBuild("plugins")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
        mavenLocal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        mavenLocal()
    }
}
rootProject.name = "Gallery"
include(":app")
include(":baselineprofile")
include(":libs:gesture")
project(":libs:gesture").projectDir = file("../ab_cloud-libs-shared/libs/gesture")
// Shared BY REFERENCE from ab_cloud-libs-shared/ — one copy, no per-app drift.
// projectDir is mandatory here: the default would resolve to ./libs/analytics,
// which is this fork's own local libs/ tree, not the shared module.
include(":libs:analytics")
// libs:media-center — OUR cloud provider/data engine, split out of app/ so the
// stable half (provider contract, Room schema, Glide plumbing) versions apart
// from the UI. Shared by reference like every other libs:* module.
include(":libs:media-center")
project(":libs:media-center").projectDir =
    file("../ab_cloud-libs-shared/libs/media-center")
project(":libs:analytics").projectDir = file("../ab_cloud-libs-shared/libs/analytics")
include(":libs:cropper")
project(":libs:cropper").projectDir = file("../ab_cloud-libs-shared/libs/cropper")
include(":libs:panoramaviewer")
project(":libs:panoramaviewer").projectDir = file("../ab_cloud-libs-shared/libs/panoramaviewer")
include(":libs:scrollbar")
project(":libs:scrollbar").projectDir = file("../ab_cloud-libs-shared/libs/scrollbar")

// ':libs:<x>' implicitly declares an intermediate ':libs' project whose default
// projectDir is <root>/libs. This app HAD one, so it never needed mapping —
// until its four modules moved into the shared root and the directory went
// away. Gradle 9 fails outright on a project directory that does not exist
// ("Configuring project ':libs' without an existing directory is not allowed"),
// which is what broke ship-cloud-media-center. Map the container at the shared
// root; every leaf above already names its own projectDir, so this only gives
// the container something real to point at.
project(":libs").projectDir = file("../ab_cloud-libs-shared/libs")
include(":ml-models")