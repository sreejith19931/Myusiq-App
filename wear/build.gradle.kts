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
        versionCode = 10101
        versionName = "1.1.1"
    }

    signingConfigs {
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        val keystoreProperties = Properties()
        if (keystorePropertiesFile.exists()) {
            keystoreProperties.load(keystorePropertiesFile.inputStream())
        }

        val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            ?: keystoreProperties.getProperty("storeFile")
            ?: "release.jks"
        val storePass = System.getenv("RELEASE_STORE_PASSWORD")
            ?: keystoreProperties.getProperty("storePassword")
        val alias = System.getenv("RELEASE_KEY_ALIAS")
            ?: keystoreProperties.getProperty("keyAlias")
        val keyPass = System.getenv("RELEASE_KEY_PASSWORD")
            ?: keystoreProperties.getProperty("keyPassword")

        val keystoreFile = rootProject.file(storeFilePath)

        create("release") {
            if (storePass != null && alias != null && keyPass != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                initWith(getByName("debug"))
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
