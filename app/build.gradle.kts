import java.security.KeyStore
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Firebase (groups feature): the real google-services.json is never committed. CI decodes it from
// the KHATWA_GOOGLE_SERVICES_BASE64 secret; local builds and forks fall back to the placeholder,
// which builds fine and makes the app show "groups not configured in this build".
run {
    val real = file("google-services.json")
    if (!real.exists()) real.writeText(file("google-services.placeholder.json").readText())
}

// Release signing: CI decodes the keystore from secrets into keystore.properties + a .jks file.
// When they are absent (local builds, forks) the release APK is signed with the debug key so it
// is still installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

// PKCS12 keystores keep one password for the store and the key (keytool silently ignores a
// different -keypass). Pick whichever password actually unlocks the key so a mismatched
// KHATWA_KEY_PASSWORD secret cannot break the release build.
val releaseKeyPassword: String? = if (!hasReleaseKeystore) null else run {
    val storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
    val storePassword = keystoreProps.getProperty("storePassword") ?: ""
    val alias = keystoreProps.getProperty("keyAlias") ?: ""
    val candidates = listOf(keystoreProps.getProperty("keyPassword") ?: "", storePassword)
    candidates.firstOrNull { candidate ->
        runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            storeFile.inputStream().use { ks.load(it, storePassword.toCharArray()) }
            ks.getKey(alias, candidate.toCharArray()) != null
        }.getOrDefault(false)
    }.also { if (it == null) logger.warn("khatwa: no candidate password unlocks key '$alias' in ${storeFile.name}") }
        ?: keystoreProps.getProperty("keyPassword")
}

android {
    namespace = "com.khatwa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khatwa.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("khatwa.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("khatwa.versionName") as String?) ?: "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        animationsDisabled = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.vico.compose.m3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
