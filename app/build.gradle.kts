import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI can drop an app/keystore.properties + keystore to produce a properly
// signed release. Without one, release builds fall back to the debug key so
// every APK stays sideload-installable.
val keystoreProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.giastudio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.giastudio.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    // The C++ audio core (Oboe + plugin engine) is compiled only when Gradle
    // is invoked with -PwithNative, so a missing NDK never blocks the
    // Kotlin-only build. GitHub Actions passes the flag.
    val withNative = gradle.startParameter.projectProperties.containsKey("withNative")
    if (withNative) {
        ndkVersion = "26.1.10909125"
        defaultConfig {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += "-DANDROID_STL=c++_shared"
                }
            }
        }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
