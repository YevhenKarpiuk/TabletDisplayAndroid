# TabletDisplayAndroid

Android-приёмник для использования планшета как дополнительного монитора Windows 11 по USB/ADB.

- Парный проект: `C:\Users\ka\Documents\c-plus-plus\TabletDisplayWindows`.
- Устройство MVP: Lenovo TB-X606F, Android 10, 1920×1200@60.
- Стек: Kotlin, Android SDK 36, minSdk 26, Gradle 9.2.1, JDK 17, `SurfaceView`, `MediaCodec`.
- Сеть: только `127.0.0.1:27183`; Windows подключается через `adb forward`; protocol v1 в `doc/PROTOCOL.md`.
- Команды: `build.cmd`, `test.cmd`; APK — `app\build\outputs\apk\debug\app-debug.apk`.
- Готово: loopback server/relisten, strict parser/session, AVC MediaCodec → Surface, одно-кадровый render pacing, асинхронная bounded-очередь `DOWN/MOVE/UP/CANCEL` по тому же TCP-сеансу.
- Lenovo TB-X606F: виртуальный дисплей 1280×800@60 декодируется аппаратным `OMX.MTK.VIDEO.DECODER.AVC`; проверено 1800 кадров за 30,01 с и 332 отправленных touch-события.
- Далее: субъективная проверка точности tap/drag, длительный reconnect/soak-тест и политика восстановления keyframe/переполнения очередей.
- Подробный план: [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
