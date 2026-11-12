plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "tech.amditis.slackcarbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.amditis.slackcarbridge"
        // API 31 is the first with PendingIntent.isActivity and isImmutable, which
        // the reply checks need. Both target phones run newer versions.
        minSdk = 31
        // Raise only after reading that version's behavior changes and retesting on both phones.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-experiment"
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
