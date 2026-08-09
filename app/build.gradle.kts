plugins {
    id("com.android.application")
}

android {
    namespace = "app.shengdu.audio"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.shengdu.audio"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.9"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    testImplementation("junit:junit:4.12")
}
