# Сборка

Стек и версии SDK — решение [D-004](../../DECISIONS/D-004-STACK-AND-VERSION-0.0.0.md), отладочные сборки — [D-005](../../DECISIONS/D-005-DEBUG-BUILDS.md).

## Что нужно

- JDK 21: на нём собирается Kotlin (`jvmToolchain(21)` в `app/build.gradle.kts`).
- Android SDK. `tools/setup-android-sdk.sh` ставит command-line tools, платформу и build-tools и пишет `sdk.dir` в `local.properties`. Если SDK уже есть — `ANDROID_HOME` или `sdk.dir` в `local.properties`.
- Gradle не нужен: `./gradlew` скачивает версию из `gradle/wrapper/gradle-wrapper.properties` и сверяет её SHA-256.

## Команды

| Команда | Что делает |
|---|---|
| `./gradlew :app:assembleDebug` | отладочный APK — `app/build/outputs/apk/debug/app-debug.apk` |
| `./gradlew :core:model:test :app:testDebugUnitTest` | unit-тесты на JVM: правила времени (`:core:model`), приложения и миграций базы |
| `./gradlew :app:lintDebug` | Lint; предупреждения — ошибки |
| `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest*'` | снимки экранов на JVM (Robolectric, D-029) — `app/build/screenshots/*.png`; первый раз Gradle качает образ Android 16 (213 МБ) |
| `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*SyncRepositoryTest*'` | синхронизация в базе (Robolectric, D-030); тот же образ Android |
| `python3 tools/check-core-api.py` | после сборки `:core:model`: вызовы Java в модели есть в Android minSdk (31) — Lint этот модуль не проверяет (D-020, п. 7) |
| `python3 tools/make-zones.py` | пересобрать таблицу поясов мировых часов из `zone.tab` базы tz (D-019) |
| `tools/debug-apk.sh` | APK из закоммиченного дерева — `build/debug-apk/debug-<коммит>.apk` и его SHA-256 |
| `tools/debug-apk.sh --publish` | то же и новым коммитом в ветку `debug-builds` |

## Отладочные сборки

- Ветка `debug-builds` — без истории `main`, только APK; каждая сборка — новый коммит, прежние файлы остаются.
- Имя файла — `debug-<короткий SHA>.apk`; незакоммиченные изменения скрипт не собирает.
- Все отладочные сборки подписаны общим ключом `app/debug.keystore` и ставятся поверх друг друга.
- Строки, которые нужно дописать в конец сообщения коммита сборки (например, подпись автора коммита), передаются переменной `DEBUG_APK_TRAILERS`.

Поставить на телефон: открыть файл в ветке `debug-builds` на GitHub, скачать и открыть его на телефоне; при первой установке разрешить установку из этого источника.

## Сбои загрузки

Первая сборка скачивает Gradle, плагины и библиотеки. Maven Central иногда отвечает `429 Too Many Requests` на всплеск запросов: это сбой сети, а не кода, — сборку повторить. Чтобы Gradle сам повторял запросы дольше, в `~/.gradle/gradle.properties` (настройки машины, не репозитория):

```
systemProp.org.gradle.internal.repository.max.tentatives=8
systemProp.org.gradle.internal.repository.initial.backoff=2000
```

Если 429 и ложные 404 не проходят, Maven Central можно брать с зеркала Google — тоже настройкой машины, файлом `~/.gradle/init.d/maven-central-mirror.gradle`:

```groovy
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def redirect = { RepositoryHandler repos ->
    repos.withType(MavenArtifactRepository).configureEach { repo ->
        if (repo.url.toString().startsWith('https://repo.maven.apache.org/maven2')) repo.url = mirror
    }
}
settingsEvaluated { settings ->
    redirect(settings.pluginManagement.repositories)
    redirect(settings.dependencyResolutionManagement.repositories)
}
```

После неудачных загрузок Gradle помнит «не найдено»: первая сборка после смены — с `--refresh-dependencies`.

Две сборки в одном каталоге одновременно не запускать: они портят промежуточные файлы друг друга.

## База

Схема Room выгружается при сборке в `app/schemas/` и коммитится: по ней пишутся и проверяются миграции (D-009, п. 4). Изменилась схема — новая версия `ClockDatabase.VERSION`, миграция в `ClockDatabase.MIGRATIONS` (`data/Migrations.kt`) и проверки данных в `MigrationTest`; сверку со схемой тест делает сам для каждой миграции.

## Версии

- Зависимости и плагины — `gradle/libs.versions.toml`; новая зависимость — со строкой в [docs/PROVENANCE.md](../PROVENANCE.md).
- `compileSdk`, `targetSdk`, `minSdk` и build-tools — `app/build.gradle.kts`; `compileSdk` и build-tools те же, что в `tools/setup-android-sdk.sh`.
- `versionName` и `versionCode` — `app/build.gradle.kts`.
