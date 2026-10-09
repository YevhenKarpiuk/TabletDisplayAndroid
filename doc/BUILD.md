# Сборка TabletDisplayAndroid

## Зафиксированное окружение

- Android Gradle Plugin `9.0.1` со встроенным Kotlin.
- Gradle `9.2.1`; JDK `17.0.13`.
- Android SDK `C:\tools\android\sdk`, Build Tools `36.1.0`.
- `compileSdk 36`, `targetSdk 36`, `minSdk 26`.

## Команды

Из корня проекта:

```cmd
build.cmd
test.cmd
C:\tools\android\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
C:\tools\android\sdk\platform-tools\adb.exe shell am start -W -n com.tabletdisplay/.MainActivity
```

Скрипты используют portable Gradle/JDK/SDK из `C:\tools` и кэш `C:\Users\ka\.gradle`; Android Studio не требуется.

## Текущее состояние

- полноэкранная `SurfaceView` и lifecycle-aware loopback server на `127.0.0.1:27183`;
- handshake, `DISPLAY_INFO`, `PING/PONG`, strict incremental parser и общие golden vectors;
- обработка `VIDEO_CONFIG`/`VIDEO_FRAME`, MediaCodec AVC с прямым выводом в Surface;
- аппаратный decoder выбирается первым, software decoder используется как fallback;
- входные PTS переводятся в локальную монотонную шкалу Android перед выводом;
- после disconnect приложение снова слушает порт;
- проверен поток software H264 MFT Windows: 150 кадров 1280×800 через аппаратный MTK decoder, средняя codec latency ≈9,7 мс.

На Lenovo TB-X606F аппаратно проверены synthetic AVC Baseline и динамический Media Foundation H.264 stream 1280×800. PixelCopy synthetic-кадра вернул 53 цвета; media metrics для MF-потока показали 150 decoder samples. Bounded queues, keyframe recovery и touch ещё не реализованы.
