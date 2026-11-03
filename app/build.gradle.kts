import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.openobd"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openobd"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.0"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: keystore.properties (local, git-ignored) or OPENOBD_* environment variables (CI).
    // Without either, release builds fall back to the debug key so they still install for testing.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun secret(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)
    val releaseStoreFile = secret("storeFile", "OPENOBD_KEYSTORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = secret("storePassword", "OPENOBD_KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "OPENOBD_KEY_ALIAS")
            keyPassword = secret("keyPassword", "OPENOBD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("screenshot.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
            // Optional Maven mirror for Robolectric's Android runtime jars: -ProbolectricRepo=https://...
            providers.gradleProperty("liveSpecs").orNull?.let { test.systemProperty("liveSpecs", it) }
            providers.gradleProperty("elmEmulator").orNull?.let { test.systemProperty("elmEmulator", it) }
            providers.gradleProperty("robolectricRepo").orNull?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            test.maxHeapSize = "3g"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.osmdroid)
    debugImplementation(libs.androidx.ui.tooling)

    // Optional AI diagnosis assistant (user-supplied Claude API key)
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Instrumented tests on real devices / emulators (connectedDebugAndroidTest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
