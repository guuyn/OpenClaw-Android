import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Load signing config from local.properties (preferred) or environment variables
val localProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

val releaseStoreFile: String? = localProperties.getProperty("RELEASE_STORE_FILE")
    ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword: String? = localProperties.getProperty("RELEASE_STORE_PASSWORD")
    ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = localProperties.getProperty("RELEASE_KEY_ALIAS")
    ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = localProperties.getProperty("RELEASE_KEY_PASSWORD")
    ?: System.getenv("RELEASE_KEY_PASSWORD")

android {
    namespace = "ai.openclaw.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.openclaw.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndkVersion = "27.0.12077973"
        
        // 只保留 ARM 架构，移除 x86/x86_64（仅模拟器需要）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null && releaseStorePassword != null &&
                releaseKeyAlias != null && releaseKeyPassword != null
            ) {
                storeFile = file("../$releaseStoreFile")
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        // Global unified debug keystore - shared across ALL Android projects
        // Location: E:\Android\keystores\debug.keystore (WSL2: /mnt/e/Android/keystores/debug.keystore)
        // SHA-256: A3:48:0C:D7:EB:37:2A:76:48:60:72:D3:D2:F2:E0:5F:45:88:62:7A:21:CD:DD:62:61:54:60:5B:80:8E:B9:45
        create("unifiedDebug") {
            storeFile = if (System.getProperty("os.name").lowercase().contains("linux"))
                file("/mnt/e/Android/keystores/debug.keystore")
            else
                file("E:/Android/keystores/debug.keystore")
            storePassword = "openclaw123"
            keyAlias = "openclaw-debug"
            keyPassword = "openclaw123"
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Debug 也启用优化减小体积
            isMinifyEnabled = false
            isShrinkResources = false
            // Use unified debug keystore for consistent signatures across WSL2 and Windows
            signingConfig = signingConfigs.getByName("unifiedDebug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Koin DI
    implementation("io.insert-koin:koin-android:3.5.3")

    // Script Engine
    implementation(project(":script"))
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Security (for encrypted preferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SQLCipher (encrypted database)
    implementation("net.zetetic:sqlcipher-android:4.10.0")

    // Room (for memory system)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // WorkManager (for periodic memory maintenance)
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // LiteRT (successor to TensorFlow Lite, 16KB page aligned)
    implementation("com.google.ai.edge.litert:litert:2.1.3")

    // LiteRT-LM (on-device Gemma 4 E4B inference)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // Rhino JS Engine (prototype for QuickJS)
    implementation("org.mozilla:rhino:1.7.15")

    // A2UI Component Library
    implementation(project(":android_compose"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("org.mockito:mockito-android:5.14.2")
    androidTestImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.16")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
