plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "bih.daily.chetna"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "bih.daily.chetna"
        // LiteRT-LM's AAR declares minSdk 23, but the offline stack is only validated from 26.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST}"
            merges += "META-INF/INDEX.LIST"
        }
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
        // The LiteRT-LM classes are compiled to Java 21 bytecode (class file major 65).
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)   // LiteRT-LM is Kotlin based
    implementation(libs.appcompat)
    implementation(libs.material)        // also supplies RecyclerView transitively
    implementation(libs.activity)        // EdgeToEdge + ActivityResultLauncher
    implementation(libs.constraintlayout)
    implementation(libs.lite.rt.lm)      // on-device inference engine
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
