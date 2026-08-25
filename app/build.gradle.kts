plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.antigravity.filemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antigravity.filemanager"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "DROPBOX_APP_KEY", "\"vs283xazcp12pj9\"")
        buildConfigField("String", "DROPBOX_REDIRECT_URI", "\"com.antigravity.filemanager://oauth2redirect\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"39598101699-28epn9art6v9i8gd5eeqk6c5tq5531ja.apps.googleusercontent.com\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Coil Image Loader
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Media3 / ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // Embedded FTP Server & Logging
    implementation(libs.apache.ftpserver.core)
    implementation(libs.slf4j.android)

    // Zip4j Archive Management
    implementation(libs.zip4j)

    // OkHttp REST & Network Client
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Google Play Services Auth + official Drive API client
    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android) {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
    }
    implementation(libs.google.api.services.drive) {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
    }

    // Dropbox official SDK (OAuth2/PKCE via Custom Tabs)
    implementation(libs.dropbox.core.sdk)
    implementation(libs.androidx.browser)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
}

