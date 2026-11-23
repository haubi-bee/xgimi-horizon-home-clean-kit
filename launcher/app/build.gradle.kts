import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Optional release signing. Create `keystore.properties` next to `settings.gradle.kts` with
 * `storeFile`, `storePassword`, `keyAlias` and `keyPassword` (the file is gitignored). Without it,
 * `assembleRelease` still works and produces an unsigned APK.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "tv.cleanhome.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.cleanhome.launcher"
        minSdk = 26
        // Deliberately 29: direct file access to /sdcard/Pictures (requestLegacyExternalStorage)
        // and no package-visibility restrictions for the app row.
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // The app is sideloaded, never published on Google Play; targetSdk 29 is intentional (see above).
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // The Android stub of org.json throws in local unit tests; the reference implementation does not.
    testImplementation("org.json:json:20240303")
}
