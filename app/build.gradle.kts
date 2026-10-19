// Приложение DP_Clock: стек и версии SDK — D-004, отладочные сборки — D-005.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dpsmpx.dpclock"
    // compileSdk и build-tools совпадают с tools/setup-android-sdk.sh.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.dpsmpx.dpclock"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.0"
    }

    signingConfigs {
        // Общий отладочный ключ (D-005): все отладочные сборки ставятся поверх
        // друг друга, из какой бы сессии они ни пришли. Не секрет и не ключ
        // выпуска.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Сверяет версии с сетью: новый выпуск чужой библиотеки ронял бы сборку без
        // изменений в коде. Версии меняются осознанно, в gradle/libs.versions.toml.
        disable += "NewerVersionAvailable"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}
