plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.acme.rc"
    compileSdk = 35

    val rcRelayUrl =
        (findProperty("rcRelayUrl") as String?)
            ?: System.getenv("RC_RELAY_URL")
            ?: "ws://10.0.2.2:8787/ws"
    val rcDeviceToken =
        (findProperty("rcDeviceToken") as String?)
            ?: System.getenv("RC_DEVICE_TOKEN")
            ?: "dev-device"

    fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    val isReleaseBuild = gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = true)
    }
    if (isReleaseBuild && !rcRelayUrl.startsWith("wss://")) {
        throw GradleException(
            "Release builds need a WSS relay URL: " +
                "-PrcRelayUrl=wss://rc.example.com/ws"
        )
    }
    if (isReleaseBuild && rcDeviceToken == "dev-device") {
        throw GradleException(
            "Release builds need the shared device token: " +
                "-PrcDeviceToken=<RC_DEVICE_TOKEN>"
        )
    }

    defaultConfig {
        applicationId = "com.acme.rc"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "RELAY_URL", quoted(rcRelayUrl))
        buildConfigField("String", "DEVICE_TOKEN", quoted(rcDeviceToken))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
