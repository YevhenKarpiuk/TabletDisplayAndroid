# TabletDisplayAndroid

Android-приёмник для использования планшета как дополнительного монитора Windows 11 по USB/ADB.

- Парный проект: `C:\Users\ka\Documents\c-plus-plus\TabletDisplayWindows`.
- Устройство MVP: Lenovo TB-X606F, Android 10, 1920×1200@60.
- Стек: Kotlin, Android SDK 36, minSdk 26, Gradle 9.2.1, JDK 17, `SurfaceView`, `MediaCodec`.
- Сеть: только `127.0.0.1:27183`; Windows подключается через `adb forward`; protocol v1 в `doc/PROTOCOL.md`.
- Команды: `build.cmd`, `test.cmd`; APK — `app\build\outputs\apk\debug\app-debug.apk`.
- Готово: loopback server, strict parser/session, VIDEO_CONFIG/VIDEO_FRAME, AVC MediaCodec → Surface, lifecycle/reconnect-to-listen.
- Проверено на Lenovo: synthetic/MF H.264 и реальный DXGI desktop stream 1280×800 отображаются через аппаратный `OMX.MTK.VIDEO.DECODER.AVC`; фон задаётся только родителю, не `SurfaceView`.
- Далее: bounded queue/keyframe recovery, IddCx stream, touch и reconnect.
- Подробный план: [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
