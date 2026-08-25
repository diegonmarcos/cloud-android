import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        // Cloud Dialer: the INSTALLED package id. Defaults to APP_ID (stock
        // org.fossify.phone) but the engine passes -PAPPLICATION_ID=
        // com.diegonmarcos.comms.dialer (build.json::forks.dialer.build.gradle_props)
        // so our fork has its OWN unique package — it never collides with a
        // stock Fossify Phone install, which is what let Android grant it the
        // default-Phone-app (ROLE_DIALER) role. The `namespace` (R/BuildConfig
        // package) intentionally stays APP_ID = org.fossify.phone (the code pkg).
        applicationId = (project.findProperty("APPLICATION_ID") ?: project.property("APP_ID")).toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        setProperty("archivesBaseName", "phone-$versionCode")

        // ---- Cloud Dialer self-contained updater (GHCR/WorkManager) ----
        // All values are data-driven: supplied via gradle -P properties by the
        // ea_cloud-comms engine (forks.dialer.build.gradle_props in build.json),
        // each with a safe in-repo default for standalone builds.
        buildConfigField("String", "GHCR_REGISTRY", "\"${project.findProperty("GHCR_REGISTRY") ?: "ghcr.io"}\"")
        buildConfigField("String", "GHCR_NAMESPACE", "\"${project.findProperty("GHCR_NAMESPACE") ?: "diegonmarcos"}\"")
        buildConfigField("String", "GHCR_IMAGE", "\"${project.findProperty("GHCR_IMAGE") ?: "cloud-comms-dialer"}\"")
        buildConfigField("String", "AUTO_UPDATE_TAG", "\"${project.findProperty("AUTO_UPDATE_TAG") ?: "latest"}\"")
        buildConfigField("String", "AUTO_UPDATE_TAG_MAP", "\"${project.findProperty("AUTO_UPDATE_TAG_MAP") ?: ""}\"")
        buildConfigField("String", "GIT_SHORT_SHA", "\"${project.findProperty("GIT_SHORT_SHA") ?: "dev"}\"")
        buildConfigField("boolean", "AUTO_UPDATE_ENABLED", "${project.findProperty("AUTO_UPDATE_ENABLED") ?: "false"}")
        buildConfigField("boolean", "AU_REQUIRE_UNMETERED", "${project.findProperty("AU_REQUIRE_UNMETERED") ?: "true"}")
        buildConfigField("boolean", "AU_REQUIRE_CHARGING", "${project.findProperty("AU_REQUIRE_CHARGING") ?: "false"}")
        buildConfigField("long", "AUTO_UPDATE_INTERVAL_HOURS", "${project.findProperty("AUTO_UPDATE_INTERVAL_HOURS") ?: "24"}L")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.indicator.fast.scroll)
    implementation(libs.autofit.text.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.eventbus)
    implementation(libs.libphonenumber)
    implementation(libs.geocoder)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    detektPlugins(libs.compose.detekt)
}
