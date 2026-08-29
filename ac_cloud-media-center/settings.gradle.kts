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
// Shared BY REFERENCE from ab_cloud-libs-shared/ — one copy, no per-app drift.
// projectDir is mandatory here: the default would resolve to ./libs/analytics,
// which is this fork's own local libs/ tree, not the shared module.
include(":libs:analytics")
project(":libs:analytics").projectDir = file("../ab_cloud-libs-shared/libs/analytics")
include(":libs:cropper")
include(":libs:panoramaviewer")
include(":libs:scrollbar")
include(":ml-models")