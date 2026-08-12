import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.streamflixreborn.streamflix"
    compileSdk = 36

    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(properties::load)
    }

    defaultConfig {
        applicationId = "com.streamflixreborn.streamflix"
        minSdk = 21
        targetSdk = 35
        versionCode = 158
        versionName = "1.7.229.4"

        buildConfigField("String", "APP_LAYOUT", "\"${properties.getProperty("APP_LAYOUT") ?: "universal"}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${properties.getProperty("TMDB_API_KEY") ?: ""}\"")
        buildConfigField("String", "SUBDL_API_KEY", "\"${properties.getProperty("SUBDL_API_KEY") ?: ""}\"")
        buildConfigField("String", "RABBITSTREAM_SOURCE_API", "\"${properties.getProperty("RABBITSTREAM_SOURCE_API") ?: ""}\"")
        buildConfigField("String", "GOOGLE_DRIVE_API_KEY", "\"${properties.getProperty("GOOGLE_DRIVE_API_KEY") ?: ""}\"")
        buildConfigField("String", "UPROT_MSFI_API_BASE", "\"${properties.getProperty("UPROT_MSFI_API_BASE") ?: ""}\"")
        buildConfigField("String", "UPROT_MSE_API_BASE", "\"${properties.getProperty("UPROT_MSE_API_BASE") ?: ""}\"")
        buildConfigField("String", "UPROT_API_KEY", "\"${properties.getProperty("UPROT_API_KEY") ?: ""}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        // The SuspiciousIndentation detector crashes (IllegalArgumentException in
        // IndentationDetector/Location) on several Kotlin files in this project.
        // This is an upstream lint bug; the check is disabled to keep lint usable.
        disable += "SuspiciousIndentation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-nowarn")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.media3.datasource.okhttp)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(project(":navigation"))

    implementation(libs.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.leanback)
    implementation(libs.glide)
    implementation(libs.glide.okhttp3.integration)
    ksp(libs.glide.ksp)
    implementation(libs.work.runtime.ktx)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.logging.interceptor)
    implementation(project(":retrofit-jsoup-converter"))
    implementation(libs.retrofit.converter.gson)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.jsoup)

    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.leanback.preference)

    implementation(libs.tvprovider)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.material)
    ksp(libs.room.compiler)

    implementation(libs.rhino)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.conscrypt.android)
    implementation(libs.browser)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.java.websocket)
}

ksp {
    arg("com.bumptech.glide.GlideModule", "AppGlideModule")
}

// --- Code quality (Fase 0: ktlint + detekt) ---
ktlint {
    // Baseline generated with `./gradlew :app:ktlintGenerateBaseline`: existing
    // violations are pinned so the build stays green, while NEW violations fail.
    baseline.set(file("ktlint-baseline.xml"))
}

detekt {
    buildUponDefaultConfig = true
    // Baseline generated with `./gradlew :app:detektBaseline`: it captures the
    // existing debt so new violations fail the build without blocking legacy code.
    baseline = file("detekt-baseline.xml")
}
