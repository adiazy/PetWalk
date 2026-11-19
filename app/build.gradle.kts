plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alexdiaz.mailtriage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alexdiaz.mailtriage"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        // Redirect scheme for Microsoft (Hotmail/Outlook) sign-in.
        // Must match the redirect URI registered in Azure: com.alexdiaz.mailtriage://oauth
        manifestPlaceholders["appAuthRedirectScheme"] = "com.alexdiaz.mailtriage"
    }

    // Fixed signing key so each new build installs as an update over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md", "META-INF/LICENSE.md",
                "META-INF/NOTICE", "META-INF/LICENSE", "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // IMAP (JavaMail for Android)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // HTTP for the Claude API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Microsoft OAuth sign-in (Hotmail / Outlook.com)
    implementation("net.openid:appauth:0.11.1")

    // Encrypted storage for passwords, tokens and API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
