# TabletDisplayAndroid

Android-приёмник системы, превращающей планшет в дополнительный монитор Windows 11 по USB/ADB.

- Парный проект: `C:\Users\ka\Documents\c-plus-plus\TabletDisplayWindows`.
- MVP: H.264 1280×800/1920×1200, 30/60 FPS, fullscreen Surface, touch → Windows.
- Стек: Kotlin, Android API 26+, MediaCodec, SurfaceView, TCP loopback, Gradle 9.2.1/AGP 9.0.1.
- Listener: только `127.0.0.1:27183`; Windows подключается через `adb forward`.
- Протокол: синхронная копия `doc/PROTOCOL.md`; односторонние изменения запрещены.
- Сборка/тест: `build.cmd`, `test.cmd`; JDK/SDK/Gradle находятся в `C:\tools`.
- Готово: lifecycle server, handshake/PING, strict parser/golden tests, VIDEO_CONFIG/VIDEO_FRAME, MediaCodec → Surface.
- Decoder предпочитает аппаратный AVC, имеет software fallback и привязывает входные PTS к локальным монотонным часам Android.
- Проверено на Lenovo TB-X606F (Android 10, 1920×1200@60): synthetic AVC Baseline 1280×800 декодирован аппаратно; PixelCopy обнаружил 53 цвета.
- Далее: bounded video queue/backpressure, keyframe recovery, длительный reconnect-тест, touch и lifecycle hardening.
- Подробный план: [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
