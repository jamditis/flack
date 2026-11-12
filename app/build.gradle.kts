plugins {
    alias(libs.plugins.android.application)
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    testImplementation(libs.junit)
}
