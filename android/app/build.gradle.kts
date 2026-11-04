plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.m1omg.opuscast"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.m1omg.opuscast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // The platform-independent part (DLNA control, file server, subtitles) is shared with core/,
    // which has its own build so its tests run without the Android SDK.
    sourceSets["main"].java.srcDirs("src/main/kotlin", "../core/src/main/kotlin")

    signingConfigs {
        // CI signs releases with the key from the repository secrets when they're set (see .github/workflows/android.yml)
        val store = System.getenv("OPUSCAST_KEYSTORE")
        if (store != null && file(store).exists()) {
            create("release") {
                storeFile = file(store)
                storePassword = System.getenv("OPUSCAST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPUSCAST_KEY_ALIAS")
                keyPassword = System.getenv("OPUSCAST_KEY_PASSWORD")
            }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
