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
        // Android 6.0+: many car head units (MTK, Rockchip PX3/PX5, Allwinner T3) still run Android 6-8.
        minSdk = 23
        targetSdk = 35
        versionCode = 33
        versionName = "1.23.1"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional donation page (gradle.properties: openobd.donateUrl). Builds for Google Play pass -Pplay,
        // which leaves it out: Play's payments policy does not allow links to outside donation pages.
        val donateUrl = if (project.hasProperty("play")) "" else (project.findProperty("openobd.donateUrl") as String?).orEmpty().trim()
        buildConfigField("String", "DONATE_URL", "\"$donateUrl\"")
        // Updates from GitHub inside the app; Google Play builds leave it out (the store updates them).
        buildConfigField("boolean", "SELF_UPDATE", if (project.hasProperty("play")) "false" else "true")
    }

    // Release signing: keystore.properties (local, git-ignored) or OPENOBD_* environment variables (CI).
    // Without either, release builds fall back to the debug key so they still install for testing.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun secret(prop: String, env: String): String? = (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    val releaseStoreFile = secret("storeFile", "OPENOBD_KEYSTORE_FILE")
    // Play builds drop the permission to install updates (src/play-release/AndroidManifest.xml removes it).
    if (project.hasProperty("play")) sourceSets.getByName("release").manifest.srcFile("src/play-release/AndroidManifest.xml")
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
        // java.time and friends on Android 6-7 (used by the app and the Anthropic SDK)
        isCoreLibraryDesugaringEnabled = true
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
            // Failures show their message in the build log (CI only keeps the log).
            test.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; events("failed") }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Google sign-in for syncing with the user's own Drive (optional: the app works without Play services).
    implementation(libs.play.services.auth)
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
    implementation(libs.usb.serial)
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
