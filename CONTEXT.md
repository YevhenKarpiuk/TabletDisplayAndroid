# TabletDisplayAndroid

Android-приёмник для использования планшета как дополнительного монитора Windows 11 по USB/ADB.

- Парный проект: `C:\Users\ka\Documents\c-plus-plus\TabletDisplayWindows`.
- Устройство MVP: Lenovo TB-X606F, Android 10, 1920×1200/1200×1920@60.
- Стек: Kotlin, Android SDK 36, minSdk 26, Gradle 9.2.1, JDK 17, `SurfaceView`, `MediaCodec`.
- Сеть: только `127.0.0.1:27183`; Windows подключается через `adb forward`; protocol v1 в `doc/PROTOCOL.md`.
- Команды: `build.cmd`, `test.cmd`, `release.cmd`; release APK — `dist\TabletDisplayAndroid-0.1.2.apk`.
- Готово: loopback server/relisten, strict parser/session, AVC MediaCodec → Surface с немедленным выводом на ближайшей развёртке, чёрный экран вне streaming, асинхронная bounded-очередь `DOWN/MOVE/UP/CANCEL` по тому же TCP-сеансу.
- Lenovo TB-X606F: виртуальный дисплей 1280×800@60 декодируется аппаратным `OMX.MTK.VIDEO.DECODER.AVC`; проверены tap/drag, разрыв соединения и автоматическое восстановление Host.
- На TB-X606F отказ от будущих render timestamps устранил накопление рассинхронизации часов: decoder→display снизился примерно с 69–74 до 33–45 мс, Surface queue — с 3 до 2 кадров.
- Release 0.1.2 подписывается локальным ключом из `signing` (каталог исключён из Git); launcher icon находится в mipmap-ресурсах.
- Далее: длительный reconnect/soak-тест и политика восстановления keyframe/переполнения очередей.
- Подробный план: [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
