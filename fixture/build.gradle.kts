// A test-only fake Slack. It posts Slack-shaped notifications with an inline
// reply, so Flack can be tested on an emulator or the Desktop Head Unit without
// real Slack messages. It is never released, and only debug builds of Flack
// accept its package.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "tech.amditis.flack.fixture"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.amditis.flack.fixture"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "test"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(libs.androidx.core)
}
