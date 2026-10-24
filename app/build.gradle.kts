// Приложение DP_Clock: стек и версии SDK — D-004, отладочные сборки — D-005.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
        versionCode = 5
        versionName = "0.4.0"
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
        // Версия — в «О приложении».
        buildConfig = true
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

ksp {
    // Схемы базы — в репозитории: по ним пишутся и проверяются миграции (D-009, п. 4).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    // Подтверждение пробуждения QR-кодом и снимком (D-015): камера и распознавание кодов.
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.bundled.jvm)
}
