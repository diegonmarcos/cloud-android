rootProject.name = "Phone"
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")

// Shared BY REFERENCE from ab_cloud-libs-shared/ — projectDir points at the one
// copy rather than a vendored duplicate, so the module can never drift per app.
include(":libs:analytics")
project(":libs:analytics").projectDir = file("../ab_cloud-libs-shared/libs/analytics")
