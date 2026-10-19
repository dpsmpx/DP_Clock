# Происхождение зависимостей

Код DP_Clock собственный: исходники и ассеты других приложений в проект не переносятся ([D-003](../DECISIONS/D-003-ORIGINAL-IMPLEMENTATION.md)). Здесь записана каждая сторонняя зависимость: откуда она, под какой лицензией и куда попадает. Новая зависимость добавляется вместе со строкой здесь — после проверки происхождения, лицензии и необходимости (D-003, п. 5).

Версии — `gradle/libs.versions.toml`.

| Что | Откуда | Лицензия | Где |
|---|---|---|---|
| Jetpack Compose (BOM, Material 3) | Google Maven, `androidx.compose` | Apache 2.0 | в APK |
| Activity Compose | Google Maven, `androidx.activity` | Apache 2.0 | в APK |
| Lifecycle Runtime Compose | Google Maven, `androidx.lifecycle` | Apache 2.0 | в APK |
| Стандартная библиотека Kotlin, корутины | Maven Central, `org.jetbrains.kotlin`, `org.jetbrains.kotlinx` | Apache 2.0 | в APK |
| Android Gradle Plugin, плагин Kotlin Compose | Google Maven, Maven Central | Apache 2.0 | только сборка |
| Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar`) | Gradle, версия 9.8.2 | Apache 2.0 | только сборка |
| JUnit 4 | Maven Central, `junit:junit` | EPL 1.0 | только тесты |

Уведомления о лицензиях библиотек, попадающих в APK, приложение должно показывать до первой сборки для распространения (ROADMAP, P5 — проверка лицензий).
