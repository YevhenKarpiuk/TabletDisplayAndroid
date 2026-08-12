# TabletDisplayAndroid

Android-приёмник TabletDisplay. Каркас содержит одно Kotlin-приложение с полноэкранной Surface, состояниями сеанса, loopback TCP transport и заготовкой MediaCodec H.264 decoder.

Сборка debug APK:

```cmd
build.cmd
```

APK: `app\build\outputs\apk\debug\app-debug.apk`.

Подробности: [doc/BUILD.md](doc/BUILD.md) и [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
