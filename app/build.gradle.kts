import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
    alias(libs.plugins.google.firebase.firebase.perf)
}

android {
    namespace = "com.thando1402.auralis"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.thando1402.auralis"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { localProps.load(it) }
        }
        val openRouterKey = localProps.getProperty("OPENROUTER_API_KEY", "")
        resValue("string", "default_openrouter_key", openRouterKey)
    }
    
    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.perf)
    implementation(libs.work.runtime)
    implementation(libs.firebase.analytics)
    implementation(libs.material)

    implementation(libs.firebase.ai)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.guava)
    implementation(libs.reactive.streams)
    implementation(libs.palette)

    // AndroidX Media3 for playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}