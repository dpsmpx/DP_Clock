# Происхождение зависимостей

Код DP_Clock собственный: исходники и ассеты других приложений в проект не переносятся ([D-003](../DECISIONS/D-003-ORIGINAL-IMPLEMENTATION.md)). Здесь записана каждая сторонняя зависимость: откуда она, под какой лицензией и куда попадает. Новая зависимость добавляется вместе со строкой здесь — после проверки происхождения, лицензии и необходимости (D-003, п. 5).

Версии — `gradle/libs.versions.toml`.

| Что | Откуда | Лицензия | Где |
|---|---|---|---|
| Jetpack Compose (BOM, Material 3) | Google Maven, `androidx.compose` | Apache 2.0 | в APK |
| Activity Compose | Google Maven, `androidx.activity` | Apache 2.0 | в APK |
| Lifecycle Runtime Compose | Google Maven, `androidx.lifecycle` | Apache 2.0 | в APK |
| AndroidX Core KTX | Google Maven, `androidx.core` | Apache 2.0 | в APK |
| Room (runtime; compiler — только сборка) | Google Maven, `androidx.room` | Apache 2.0 | в APK |
| CameraX (core, camera2, lifecycle, view) | Google Maven, `androidx.camera` | Apache 2.0; внутри — libyuv, BSD 3-Clause | в APK (D-015) |
| ZXing core | Maven Central, `com.google.zxing:core` | Apache 2.0 | в APK (D-015) |
| Стандартная библиотека Kotlin, корутины | Maven Central, `org.jetbrains.kotlin`, `org.jetbrains.kotlinx` | Apache 2.0 | в APK |
| Android Gradle Plugin, плагины Kotlin Compose и Kotlin JVM | Google Maven, Maven Central | Apache 2.0 | только сборка |
| KSP | Maven Central, `com.google.devtools.ksp` | Apache 2.0 | только сборка |
| Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar`) | Gradle, версия 9.8.2 | Apache 2.0 | только сборка |
| JUnit 4 | Maven Central, `junit:junit` | EPL 1.0 | только тесты |
| Встроенный SQLite для JVM (`sqlite-bundled-jvm`) | Google Maven, `androidx.sqlite` | Apache 2.0 | только тесты миграций (D-013, п. 10) |

Производственный календарь России (`core/model/.../RuCalendar.kt`, D-014) — данные официальных документов: ТК РФ, ст. 112; постановления Правительства РФ от 24.09.2025 № 1466 и от 17.09.2026 № 1187 о переносе выходных в 2026 и 2027 годах. Сверено с производственными календарями КонсультантПлюс 24.10.2026. Официальные документы не охраняются авторским правом (ГК РФ, ст. 1259, п. 6).

Часовые пояса мировых часов (D-019) — таблица `core/model/src/main/resources/com/dpsmpx/dpclock/model/zones.tsv`, собранная скриптом `tools/make-zones.py` из `zone.tab` базы tz IANA, версия 2026e. База tz — общественное достояние. Названия городов и стран берутся на телефоне из данных ICU в Android, в APK их нет.

Погода (D-019) — прогноз MET Norway (Норвежский метеорологический институт), Locationforecast 2.0, лицензия данных CC BY 4.0. Источник указан в шапке погоды («MET Norway») и в настройках. Данные не изменяются, кроме выбора текущего часа и перевода кода погоды в слова. Тестовый ответ `core/model/src/test/resources/.../met-compact.json` — сокращённый прогноз MET для Москвы за 24.10.2026, лицензия та же.

Звуки DP_Clock не файлы: ноты и тембры записаны в коде (`app/src/main/kotlin/com/dpsmpx/dpclock/platform/Synth.kt`), звук синтезируется при сигнале (D-013, п. 2). Мелодии телефона и выбранные человеком файлы в APK не входят.

Список библиотек в APK и их лицензия показаны в настройках, «О приложении» (0.1.0). Полные тексты уведомлений о лицензиях — до первой сборки для распространения (ROADMAP, P5 — проверка лицензий).
