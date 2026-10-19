# Исследование AOSP DeskClock

- **Статус:** лицензия проверена ([D-003](../../DECISIONS/D-003-OWN-CODE-AND-BORROWED-FILES.md)); отбор файлов для заимствования не выполнен.
- **Цель:** знать, что из AOSP DeskClock можно и стоит заимствовать по D-003, и сравнивать возможности. Основой DP_Clock upstream не служит.

## Источники

- AOSP DeskClock: https://android.googlesource.com/platform/packages/apps/DeskClock/
- Android Code Search: https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/DeskClock/

## Факты на 19 октября 2026

Факты исходного кода: временный клон upstream `main`, коммит `04e481f37e0b52b74c5a5c7b78b662d1f94e3478` от 27 февраля 2025. Клон удалён, в репозиторий ничего не перенесено.

- README upstream: приложение не поддерживается, исходник оставлен только как справка и будет убран из манифеста AOSP.
- Лицензия: `Android.bp` объявляет для модуля `Android-Apache-2.0`; у всех 172 файлов кода (`.kt`, `.java`) — заголовок Apache License 2.0. Отдельных файлов LICENSE и NOTICE в каталоге модуля нет.
- Правообладатель — The Android Open Source Project; в `src/com/android/deskclock/NotificationUtils.kt` также The LineageOS Project.
- Без заголовка лицензии в самом файле:
  - шрифт `assets/fonts/clock.ttf` — в метаданных «Copyright 2016 Google Inc. All Rights Reserved.», лицензии нет;
  - звуки `res/raw/alarm_expire.ogg` и `res/raw/timer_expire.ogg` — ни автора, ни лицензии;
  - изображения (127 PNG) и 14 из 376 XML в `res/`.

  По D-003 такие файлы не заимствуются.
- Сборка: только Soong (`Android.bp`, `android_app`, `sdk_version: "current"`), Gradle нет; `minSdkVersion` 23, `targetSdkVersion` 30; пакет `com.android.deskclock`.
- Зависимости: библиотеки AndroidX и Material Components.

## Отбор файлов для заимствования

- [ ] Описать модули и основные компоненты upstream настолько, чтобы найти кандидатов: планирование будильников, восстановление после перезагрузки, обработчики системных событий, часовые пояса, тесты.
- [ ] Для каждого кандидата: что даёт, что придётся изменить под архитектуру DP_Clock и новый `targetSdk`, есть ли в файле заголовок лицензии.
- [ ] Итог — список кандидатов или вывод, что заимствовать нечего. Взятые файлы — в [docs/PROVENANCE.md](../PROVENANCE.md).

## Сравнение возможностей

- [ ] Составить матрицу базовых функций upstream (будильники, повторы, мировые часы, таймеры, секундомер, звук и настройки) для сравнения с планом DP_Clock.

## Путь поставки

- [ ] Сравнить самостоятельный APK и включение своего приложения в собственную ROM.
- [ ] Определить свой application ID, подпись, сосуществование со штатными часами и обновления.
- [ ] Уточнить целевые устройства и версии Android.

## Правила записи

Каждый вывод помечать как факт исходного кода, результат эксперимента или предположение; для фактов указывать дату и коммит upstream. Сводные решения переносить в DECISIONS, следующие действия — в TODO.
