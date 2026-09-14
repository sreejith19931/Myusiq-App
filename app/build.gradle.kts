import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.music.rhythm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.music.rhythm"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
                // Default placeholders if file doesn't exist (e.g. for CI)
                storeFile = file("release-key.jks")
                storePassword = "YOUR_STORE_PASSWORD"
                keyAlias = "YOUR_KEY_ALIAS"
                keyPassword = "YOUR_KEY_PASSWORD"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.material)
    implementation(libs.glide)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.androidx.media)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.dynamicanimation.ktx)
    implementation(libs.jsoup)
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.core.splashscreen)
    ksp(libs.androidx.room.compiler)
}

ksp {
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}
