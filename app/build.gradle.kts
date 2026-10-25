import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
    alias(libs.plugins.google.firebase.firebase.perf)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
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
    implementation(libs.firebase.perf)
    implementation("com.google.firebase:firebase-analytics:22.1.2")
    implementation(libs.material)
    
    implementation("com.google.firebase:firebase-ai:18.1.0")
    implementation("com.google.firebase:firebase-appcheck-debug:20.0.0")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.reactivestreams:reactive-streams:1.0.4")
    implementation("androidx.palette:palette:1.0.0")

    // AndroidX Media3 for playback
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}