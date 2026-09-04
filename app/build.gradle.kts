import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.budjetame.android"
    compileSdk = 37 // Android 17

    // Release signing: the upload key lives outside the repo. Set
    // RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS /
    // RELEASE_KEY_PASSWORD as Gradle properties (e.g. in
    // ~/.gradle/gradle.properties) and release builds come out signed;
    // without them release builds stay unsigned (debug-only machines).
    val keystorePath = providers.gradleProperty("RELEASE_KEYSTORE_PATH").orElse("").get()

    signingConfigs {
        if (keystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    // Map provider seam (ADR-0004 parity, ticket #29): the picker's provider
    // comes from Gradle properties at build time, mirroring the web's
    // VITE_MAP_PROVIDER / VITE_GOOGLE_MAPS_API_KEY. Anything that is not
    // exactly `google` selects the free osmdroid picker (no key needed);
    // `google` requires GOOGLE_MAPS_API_KEY and fails loudly at render time
    // without it — the resolver's contract. Set them in gradle.properties
    // (or ~/.gradle/gradle.properties for a private key) and never commit a
    // key.
    val mapProvider = providers.gradleProperty("MAP_PROVIDER").orElse("leaflet").get()
    val googleMapsApiKey = providers.gradleProperty("GOOGLE_MAPS_API_KEY").orElse("").get()

    defaultConfig {
        applicationId = "com.budjetame.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "1.5.0"
        buildConfigField("String", "MAP_PROVIDER", "\"$mapProvider\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Debug builds talk to the stage deployment (ADR-0019 in the web
            // repo): a real environment, throwaway registered Accounts.
            buildConfigField("String", "API_BASE_URL", "\"https://stage.budjetame.de/api/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://budjetame.de/api/\"")
            // Ask AGP to extract native debug symbols into the AAB. Caveat:
            // today this embeds nothing, because the only .so in the app
            // (libandroidx.graphics.path.so from androidx/Compose) ships
            // pre-stripped upstream (no .symtab/.debug sections, only the
            // JNI_OnLoad export), so the Play Console "native debug symbols"
            // warning cannot be silenced for this library no matter what.
            // Keep the flag anyway: zero cost, and it self-activates the day
            // this app compiles its own NDK code, whose symbols DO get
            // embedded and auto-consumed by Play.
            ndk {
                debugSymbolLevel = "FULL"
            }
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.util.Log is a no-op in JVM unit tests (no device to log to).
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9 built-in Kotlin DSL (replaces the removed android.kotlinOptions {})
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.credentials)
    implementation(libs.googleid)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.google.maps)
    implementation(libs.places)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
