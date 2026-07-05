import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

fun strictConfig(name: String): String {
    val value = (localProperties.getProperty(name) ?: System.getenv(name)).orEmpty().trim()
    if (value.isBlank()) {
        throw GradleException(
            "Missing $name. Add it in local.properties (project root) or as an environment variable."
        )
    }
    return value
}
android {
    namespace = "com.project.smartattendance"
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        applicationId = "com.project.smartattendance"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${strictConfig("SUPABASE_URL")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${strictConfig("SUPABASE_ANON_KEY")}\""
        )
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}
dependencies {
    implementation("io.ktor:ktor-client-core:2.3.11")
    implementation("io.ktor:ktor-client-android:2.3.11")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.compose.foundation:foundation:1.5.1")
    implementation("androidx.compose.ui:ui:1.5.1")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.5.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
