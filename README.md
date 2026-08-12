# TabletDisplayAndroid

Android-приёмник TabletDisplay с loopback TCP transport и аппаратным H.264 MediaCodec, выводящим кадры напрямую в полноэкранную Surface.

Проверены как встроенный AVC fixture, так и поток настоящего Windows Media Foundation H.264 encoder: 150 кадров 1280×800 приняты аппаратным decoder на Lenovo TB-X606F.

```cmd
build.cmd
test.cmd
```

APK: `app\build\outputs\apk\debug\app-debug.apk`.

Подробности: [doc/BUILD.md](doc/BUILD.md) и [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
