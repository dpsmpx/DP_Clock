// Приложение DP_Clock: стек и версии SDK — D-004, отладочные сборки — D-005.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Образ Android для снимков экранов (D-029): только по запросу `-Pscreenshots`.
val robolectricSdk: Configuration by configurations.creating { isTransitive = false }
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricSdk = tasks.register<Copy>("copyRobolectricSdk") {
    from(robolectricSdk)
    into(robolectricJars)
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
        versionCode = 35
        versionName = "0.28.1"
    }

    signingConfigs {
        // Общий отладочный ключ (D-005): отладочные сборки ставятся поверх друг друга.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Ключ выпуска — только из окружения/секретов CI (не в репозитории).
        // Локально: export KEYSTORE_FILE=... KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
        // CI: секреты KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
        val releaseStore = System.getenv("KEYSTORE_FILE")
        if (!releaseStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: error("KEYSTORE_PASSWORD не задан")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: error("KEY_ALIAS не задан")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: error("KEY_PASSWORD не задан")
            }
        }
    }

    buildTypes {
        // Сборка выпуска (D-036, п. 2): minify. Подпись — ключ выпуска, если задан;
        // иначе debug (локальные пробы). Workflow Release требует ключ выпуска.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    // Снимки экранов на JVM (D-029): Robolectric рисует настоящие экраны с ресурсами приложения.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Снимки и другие тесты на Robolectric — по запросу: `-Pscreenshots`; обычная проверка
            // их пропускает и образ Android не качает.
            val screenshots = project.findProperty("screenshots") != null
            test.systemProperty("dpclock.screenshots", screenshots)
            test.systemProperty("dpclock.robolectric", screenshots)
            test.systemProperty("dpclock.screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.path)
            // Образ Android — из папки, куда его положил Gradle: Robolectric сам в сеть не ходит.
            test.systemProperty("robolectric.offline", "true")
            test.systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.path)
            if (screenshots) {
                test.dependsOn(copyRobolectricSdk)
            } else {
                // Без образа Android Robolectric падает ещё до теста — на чистой машине (CI) эти
                // классы в обычную проверку не входят. Новый тест на Robolectric — в этот список.
                test.exclude("**/ScreenshotsTest*", "**/SyncRepositoryTest*", "**/SunRepositoryTest*")
            }
            // Robolectric пишет в поля FileDescriptor через SharedSecrets — на Java 17+ их надо открыть.
            test.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Сверяет версии с сетью: новый выпуск чужой библиотеки ронял бы сборку без
        // изменений в коде. Версии меняются осознанно, в gradle/libs.versions.toml.
        disable += "NewerVersionAvailable"
        // То же для самого Android Gradle Plugin: выход AGP 9.5.0 ронял Lint на нетронутом коде.
        disable += "AndroidGradlePluginVersion"
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
    testImplementation(libs.robolectric)
    robolectricSdk(libs.robolectric.android.all)
}
