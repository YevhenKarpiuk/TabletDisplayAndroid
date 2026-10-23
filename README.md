# TabletDisplayAndroid

Android-приёмник TabletDisplay: показывает дополнительный монитор Windows на планшете или телефоне и передаёт касания обратно в Windows. Windows-часть (драйвер виртуального монитора и Host) — [TabletDisplayWindows](https://github.com/YevhenKarpiuk/TabletDisplayWindows).

## Возможности

- Полноэкранное видео H.264 через аппаратный `MediaCodec` в асинхронном режиме с выводом на ближайшей развёртке; если аппаратный декодер не справляется с размером, используется программный.
- Восстановление после ошибок декодера по запросу ключевого кадра без переподключения.
- Сообщает Windows разрешение экрана и пределы декодера, чтобы монитор создавался в правильном соотношении сторон.
- Касания передаются в Windows (как сенсорный экран или как мышь — выбирается в трее Windows); экран не гаснет во время трансляции.
- Ориентация: устройство поворачивается по датчику независимо от системного автоповорота, и монитор Windows поворачивается следом; из трея Windows ориентацию можно заблокировать для каждого устройства.
- USB через ADB (`127.0.0.1:27183`) или Wi-Fi без ADB (порт 27185, поиск через mDNS, сопряжение с подтверждением кода, шифрование AES-256-GCM).

## Установка

1. Установите APK из [`prebuilt/`](prebuilt) или [Releases](https://github.com/YevhenKarpiuk/TabletDisplayAndroid/releases) (Android 8.0+).
2. Установите драйвер и tray-приложение из [TabletDisplayWindows/prebuilt](https://github.com/YevhenKarpiuk/TabletDisplayWindows/tree/main/prebuilt).
3. Откройте приложение TabletDisplay. USB: включите отладку по USB и подключите кабель. Wi-Fi: выберите устройство в меню трея Windows и подтвердите одинаковый код на обоих экранах.

## Сборка

Нужны JDK 17, Android SDK 36 и Gradle 9.2.1 (скрипты ищут их в `C:\tools`, либо используется `gradlew`).

```cmd
build.cmd
test.cmd
release.cmd
```

`release.cmd` собирает подписанный release APK в `dist\`; ключ подписи хранится в каталоге `signing`, который не попадает в Git.

## Документация

- [doc/PROTOCOL.md](doc/PROTOCOL.md) — протокол v1 и Wi-Fi транспорт (одинаковый в обоих репозиториях).
- [doc/BUILD.md](doc/BUILD.md) — окружение и команды.
- [CONTEXT.md](CONTEXT.md) — текущее состояние проекта.
