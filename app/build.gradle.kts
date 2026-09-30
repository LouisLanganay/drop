plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.langanay.drop"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.langanay.drop"
        minSdk = 29
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // Même clé à chaque compilation : sans elle, Android refuse d'installer
    // une nouvelle version par-dessus l'ancienne (signature différente).
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("keystore/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // DTLS 1.2 avec clé partagée : seul chiffrement que le pont Hue accepte pour le flux temps réel.
    implementation("org.bouncycastle:bctls-jdk18on:1.79")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
}
