import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.music.myusiq.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.music.myusiq.wear"
        minSdk = 26
        targetSdk = 37
        versionCode = 10000
        versionName = "1.0.0"
    }

    signingConfigs {
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        if (keystorePropertiesFile.exists()) {
            val keystoreProperties = Properties()
            keystoreProperties.load(keystorePropertiesFile.inputStream())
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        } else {
            create("release") {
                storeFile = file("release-key.jks")
                storePassword = "YOUR_STORE_PASSWORD"
                keyAlias = "YOUR_KEY_ALIAS"
                keyPassword = "YOUR_KEY_PASSWORD"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.palette.ktx)
}
