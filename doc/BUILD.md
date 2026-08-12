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
```

`build.cmd` и `test.cmd` используют portable Gradle из `C:\tools\gradle\gradle-9.2.1`, задают `JAVA_HOME`, `ANDROID_HOME` и пользовательский Gradle-кэш только для дочернего процесса. Если portable Gradle отсутствует, используется wrapper. Android Studio не требуется.

## Состав каркаса

- полноэкранная `SurfaceView` и статус ожидания;
- loopback-only `ServerSocket` на `127.0.0.1:27183`;
- простая state machine сеанса и JVM-тесты;
- строгий incremental parser wire-протокола и общие с Windows golden-векторы;
- lifecycle-заготовка H.264 `MediaCodec`;
- модель нормализованного touch-события.

Приложение пока не запускает сервер и decoder автоматически: bounded queues и связывание parser/decoder/transport реализуются на следующем этапе.

Разрешение `INTERNET` требуется Android для IP-сокета; сам listener жёстко привязан к loopback и не принимает соединения из LAN.
