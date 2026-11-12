plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.cyclonedx)
}

android {
    namespace = "tech.amditis.flack"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.amditis.flack"
        // API 31 is the first with PendingIntent.isActivity and isImmutable, which
        // the reply checks need. Both target phones run newer versions.
        minSdk = 31
        // Raise only after reading that version's behavior changes and retesting on both phones.
        targetSdk = 36
        // The release workflow passes these from the tag and run number.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.0.0-dev"
    }

    // Release signing comes from the environment only, set by the release workflow
    // from repository secrets. The keystore is never in the repository.
    val releaseKeystore = System.getenv("FLACK_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FLACK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FLACK_KEY_ALIAS")
                keyPassword = System.getenv("FLACK_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // Debug builds also accept the test-only fake Slack app (fixture/). Release
        // builds accept the official Slack app only.
        debug {
            buildConfigField("String", "FIXTURE_PACKAGE", "\"tech.amditis.flack.fixture\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "FIXTURE_PACKAGE", "\"\"")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Robolectric needs merged resources to render the screens for screenshots.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric on Android 16 images reads JDK internals that Java 17+ hides.
        unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // targetSdk is raised deliberately, after device testing (see defaultConfig).
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.material)
    implementation(libs.androidx.car.app)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.core)
}

// Software bill of materials for the release: only what ships inside the APK.
// The release workflow attaches it and a signed attestation to each release.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
}
