plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.inod.smsguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.inod.smsguard"
        minSdk = 26
        targetSdk = 34
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciBuild ?: 1
        versionName = if (ciBuild == null) "1.0-dev" else "1.0.$ciBuild"
    }

    val stableKeystore = System.getenv("SMS_GUARD_KEYSTORE")
    val stableSigning = if (!stableKeystore.isNullOrBlank()) signingConfigs.create("stable") {
        storeFile = file(stableKeystore)
        storePassword = System.getenv("SMS_GUARD_STORE_PASSWORD")
        keyAlias = System.getenv("SMS_GUARD_KEY_ALIAS")
        keyPassword = System.getenv("SMS_GUARD_KEY_PASSWORD")
        storeType = "PKCS12"
    } else null

    buildTypes {
        debug {
            isMinifyEnabled = false
            stableSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            stableSigning?.let { signingConfig = it }
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
        viewBinding = true
        // BuildConfig.DEBUG gates the timing log that measures how long a
        // provider sync takes on a real device.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
}
