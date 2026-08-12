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
gradlew.bat --no-daemon :app:testDebugUnitTest
C:\tools\android\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

`build.cmd` задаёт `JAVA_HOME` и `ANDROID_HOME` только для дочернего процесса. Android Studio не требуется.

## Состав каркаса

- полноэкранная `SurfaceView` и статус ожидания;
- loopback-only `ServerSocket` на `127.0.0.1:27183`;
- простая state machine сеанса и JVM-тесты;
- lifecycle-заготовка H.264 `MediaCodec`;
- модель нормализованного touch-события.

Приложение пока не запускает сервер и decoder автоматически: wire parser, bounded queues и связывание компонентов реализуются после фиксации `doc/PROTOCOL.md`.

Разрешение `INTERNET` требуется Android для IP-сокета; сам listener жёстко привязан к loopback и не принимает соединения из LAN.
