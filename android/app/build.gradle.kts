plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.wire)
}

// Firebase is optional: the build (and the app) work without google-services.json.
// Drop the file into app/ to enable FCM push hints.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

fun prop(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appLinkHost = prop("photon.appLinkHost", "photon.example")

android {
    namespace = "com.photon.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photon.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APP_LINK_HOST", quoted(appLinkHost))
        buildConfigField("String", "INVITE_LINK_PREFIX", quoted("https://$appLinkHost/j/"))
        manifestPlaceholders["appLinkHost"] = appLinkHost
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE", quoted(prop("photon.debug.apiBase", "http://10.0.2.2:8080")))
            buildConfigField("String", "IMAGE_BASE", quoted(prop("photon.debug.imageBase", "http://10.0.2.2:8081")))
            buildConfigField("String", "OAUTH_REDIRECT_URI", quoted("photon://auth/cb"))
            // The dev backend builds absolute URLs (upload_url, the dev login page) from its own
            // default of localhost, which the emulator can't reach; debug builds point those at
            // API_BASE / IMAGE_BASE. See net/LocalUrls.kt.
            buildConfigField("boolean", "REWRITE_LOCAL_URLS", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE", quoted(prop("photon.release.apiBase", "https://api.$appLinkHost")))
            buildConfigField("String", "IMAGE_BASE", quoted(prop("photon.release.imageBase", "https://img.$appLinkHost")))
            buildConfigField("String", "OAUTH_REDIRECT_URI", quoted("https://$appLinkHost/auth/cb"))
            buildConfigField("boolean", "REWRITE_LOCAL_URLS", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }

    sourceSets {
        // Room schema JSON is exported here and used by migration tests.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        // The end-to-end encryption test vectors are shared with the backend (tests/vectors/e2ee_v1.json);
        // the unit tests read them in place, so the Kotlin can never drift from the Python reference.
        getByName("test").resources.srcDir(rootProject.file("../tests/vectors"))
    }
}

// E2eeCrossCheckTest writes a Kotlin-sealed box and a Kotlin-built photo file here for the Python reference to
// open; declared so a cached test run restores it.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    outputs.file(layout.buildDirectory.file("e2ee-crosscheck.json"))
}

// The wire format lives in the repository's proto/ directory and is shared with the backend.
// Generate from it in place so the client can never drift from the server.
wire {
    sourcePath {
        srcDir(rootProject.file("../proto"))
    }
    kotlin {}
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    // X25519 and HKDF for end-to-end encryption (minSdk 26 has no platform X25519).
    implementation(libs.tink.android)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)

    implementation(libs.okhttp)
    implementation(libs.wire.runtime)

    implementation(libs.cronet.okhttp)
    implementation(libs.cronet.api)
    implementation(libs.play.services.cronet)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.credentials.core)
    implementation(libs.credentials.playservices)
    implementation(libs.googleid)

    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
