# Сборка TabletDisplayAndroid

## Зафиксированное окружение

- Android Gradle Plugin `9.0.1` со встроенным Kotlin.
- Gradle wrapper `9.2.1`.
- JDK `17.0.13` из `C:\tools\java\jdk-17.0.13`.
- Android SDK из `C:\tools\android\sdk`.
- Android Build Tools `36.1.0`.
- `compileSdk 36`, `targetSdk 36`, `minSdk 26`.

## Команды

Из корня проекта:

```cmd
build.cmd
test.cmd
C:\tools\android\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
C:\tools\android\sdk\platform-tools\adb.exe shell am start -W -n com.tabletdisplay/.MainActivity
```

`build.cmd` и `test.cmd` используют portable Gradle из `C:\tools\gradle\gradle-9.2.1`, JDK/SDK из `C:\tools` и существующий кэш `C:\Users\ka\.gradle`. Если portable Gradle отсутствует, используется wrapper. Android Studio не требуется.

## Состав каркаса

- полноэкранная `SurfaceView` и статус ожидания;
- loopback-only `ServerSocket` на `127.0.0.1:27183`;
- lifecycle-сервер с повторным ожиданием соединения и JVM-тесты handshake;
- строгий incremental parser wire-протокола и общие с Windows golden-векторы;
- lifecycle-заготовка H.264 `MediaCodec`;
- модель нормализованного touch-события.

Приложение запускает loopback-сервер вместе с Activity, выполняет `HELLO_ACK`, отправляет `DISPLAY_INFO`, отвечает на `PING` и после disconnect снова переходит в ожидание. MediaCodec, bounded video queues и touch ещё не связаны с transport.

Разрешение `INTERNET` требуется Android для IP-сокета; сам listener жёстко привязан к loopback и не принимает соединения из LAN.
