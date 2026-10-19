# Происхождение заимствованного

Здесь записаны каждая зависимость и каждый файл, взятые в DP_Clock извне. Правила заимствования файлов — решение [D-003](../DECISIONS/D-003-OWN-CODE-AND-BORROWED-FILES.md); при расхождении право оно.

## Зависимости

Версии — `gradle/libs.versions.toml`; новая зависимость добавляется вместе со строкой здесь.

| Что | Откуда | Лицензия | Где |
|---|---|---|---|
| Jetpack Compose (BOM, Material 3) | Google Maven, `androidx.compose` | Apache 2.0 | в APK |
| Activity Compose | Google Maven, `androidx.activity` | Apache 2.0 | в APK |
| Lifecycle Runtime Compose | Google Maven, `androidx.lifecycle` | Apache 2.0 | в APK |
| Стандартная библиотека Kotlin, корутины | Maven Central, `org.jetbrains.kotlin`, `org.jetbrains.kotlinx` | Apache 2.0 | в APK |
| Android Gradle Plugin, плагин Kotlin Compose | Google Maven, Maven Central | Apache 2.0 | только сборка |
| Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar`) | Gradle, версия 9.8.2 | Apache 2.0 | только сборка |
| JUnit 4 | Maven Central, `junit:junit` | EPL 1.0 | только тесты |

## Как взять файл из AOSP DeskClock

1. Убедиться, что в самом файле есть заголовок Apache License 2.0. Нет заголовка — файл не берётся.
2. Взять файл из https://android.googlesource.com/platform/packages/apps/DeskClock/ по конкретному коммиту и записать этот коммит.
3. Сохранить заголовок с уведомлением об авторских правах; под ним добавить строку `Изменено в DP_Clock: <дата>, <что изменено>.`
4. Подстроить файл под архитектуру, пакет и имена DP_Clock.
5. Тем же коммитом добавить строку в таблицу ниже.
6. Если это первый заимствованный файл — тем же коммитом положить текст лицензии в `LICENSES/Apache-2.0.txt`.

До первой сборки для распространения приложение должно показывать текст Apache License 2.0 и уведомления о заимствованных файлах.

## Что не берётся

- Файлы без заголовка лицензии в самом файле: шрифты, звуки, изображения, XML без заголовка.
- Что-либо из приложений с закрытым кодом или без разрешающей лицензии, в том числе из часов Google.
- Товарные знаки «Android» и Google в названии и оформлении, пакет `com.android.deskclock`.

## Заимствованные файлы

Пока ничего не заимствовано.

| Файл в DP_Clock | Файл upstream | Коммит upstream | Дата | Что изменено |
|---|---|---|---|---|
