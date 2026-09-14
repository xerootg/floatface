import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Per-owner unlock bytes (SPEC §10): read from the gitignored unlock.properties,
// falling back to the all-zero placeholder so a clean checkout / CI builds
// without the secret. Never commit unlock.properties.
val unlockBytesHex: String = run {
    val f = rootProject.file("unlock.properties")
    val props = Properties()
    if (f.exists()) f.inputStream().use { props.load(it) }
    props.getProperty("onewheel.unlockBytesHex")?.trim().takeUnless { it.isNullOrEmpty() } ?: "0".repeat(40)
}

android {
    namespace = "app.floatface.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.floatface.wear"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = System.getenv("FLOATFACE_VERSION_NAME")?.takeUnless { it.isBlank() } ?: "0.1.0"
        buildConfigField("String", "UNLOCK_BYTES_HEX", "\"$unlockBytesHex\"")
    }

    // Release signing is configured only when a keystore is supplied via env
    // (CI secrets). Otherwise `assembleRelease` still succeeds, producing an
    // unsigned APK usable as a build artifact.
    signingConfigs {
        val keystorePath = System.getenv("FLOATFACE_KEYSTORE")
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("FLOATFACE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FLOATFACE_KEY_ALIAS")
                keyPassword = System.getenv("FLOATFACE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minification is intentionally off until a signed release is
            // validated on real hardware (Health Services / Compose reflection);
            // keeps are staged in proguard-rules.pro for when it is enabled.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // registerForActivityResult is called on a ComponentActivity (not a
        // Fragment), where it works regardless of the fragment artifact a
        // transitive dep happens to pin — so this check is a false positive here.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ble"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.services)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.input)
    implementation(libs.play.services.wearable)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
}
