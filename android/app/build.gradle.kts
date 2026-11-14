plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.iknownotes"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.iknownotes"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Backend address baked into the app. Override per build with -PbaseUrl=... or
        // `baseUrl=...` in gradle.properties; the default reaches your PC from the emulator.
        val baseUrl = (project.findProperty("baseUrl") as String?) ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    buildTypes {
        debug {
            // Testing shortcut: build with -PdevLogin=true and the login screen is skipped; the app
            // signs in (or registers) a throwaway test account by itself. Off unless you ask for it.
            // Override the account with -PdevUser=... -PdevPass=... (password needs 8+ characters).
            val devLogin = (project.findProperty("devLogin") as String?) == "true"
            val devUser = (project.findProperty("devUser") as String?) ?: "tester_dev"
            val devPass = (project.findProperty("devPass") as String?) ?: "tester-dev-123"
            buildConfigField("boolean", "DEV_AUTO_LOGIN", devLogin.toString())
            buildConfigField("String", "DEV_USERNAME", "\"$devUser\"")
            buildConfigField("String", "DEV_PASSWORD", "\"$devPass\"")
        }
        release {
            // Release builds can never contain the shortcut or its credentials.
            buildConfigField("boolean", "DEV_AUTO_LOGIN", "false")
            buildConfigField("String", "DEV_USERNAME", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}