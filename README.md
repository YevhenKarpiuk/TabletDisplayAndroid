# TabletDisplayAndroid

Android-часть TabletDisplay: отображает рабочий стол Windows на планшете
как дополнительный монитор через USB/ADB. Парный проект:
[TabletDisplayWindows](https://github.com/YevhenKarpiuk/TabletDisplayWindows).

## Возможности

- Полноэкранный вывод H.264 через MediaCodec в SurfaceView.
- Приоритет аппаратного декодера и программный fallback.
- TCP-сервер только на `127.0.0.1:27183`; Windows подключается через `adb forward`.
- Handshake, параметры дисплея, heartbeat и строгая проверка сетевых сообщений.
- Альбомная/портретная ориентация и повторное ожидание подключения после разрыва.
- Передача касаний DOWN/MOVE/UP/CANCEL обратно в Windows по тому же сеансу.
- Чёрный экран вне активного видеопотока.

## Установка и подключение

Готовый APK версии 0.1.2:
[TabletDisplayAndroid-0.1.2.apk](prebuilt/TabletDisplayAndroid-0.1.2.apk).

1. Установите APK на планшет с Android 8.0 или выше.
2. Включите USB debugging, подключите планшет к Windows и подтвердите доступ ADB.
3. Откройте приложение на планшете.
4. Установите драйвер и запустите Host из парного Windows-репозитория.

Host настраивает перенаправление порта ADB. Ручной вариант:

```cmd
adb forward tcp:27183 tcp:27183
```

## Сборка

Окружение: JDK 17, Gradle 9.2.1, Android Gradle Plugin 9.0.1 со встроенным
Kotlin, Android SDK 36 и Build Tools 36.1.0; `minSdk 26`.

Скрипты `build.cmd`, `test.cmd`, `release.cmd` рассчитаны на локальный
toolchain в `C:\tools`. Для другого окружения адаптируйте пути или
используйте Gradle wrapper с настроенными `JAVA_HOME` и Android SDK:

```cmd
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
`release.cmd` собирает и подписывает release APK локальным ключом.
Ключи подписи и пароль в репозиторий не включены; для собственной сборки
скрипт создаёт новый ключ. Он не позволяет обновлять APK, подписанный
исходным ключом, без предварительного удаления установленной версии.

## Структура

| Каталог | Назначение |
|---|---|
| `app/src/main/.../transport/` | Loopback TCP-сервер |
| `app/src/main/.../protocol/` | Формат сообщений и handshake |
| `app/src/main/.../session/` | Управление соединением |
| `app/src/main/.../video/` | MediaCodec и видеосеанс |
| `app/src/main/.../input/`, `ui/` | Касания и Surface |
| `app/src/test/`, `protocol/golden/` | Тесты и эталонные пакеты |

Протокол v1: [doc/PROTOCOL.md](doc/PROTOCOL.md); согласован с Windows-проектом.
Окружение и архитектура: [doc/BUILD.md](doc/BUILD.md),
[doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md), [CONTEXT.md](CONTEXT.md).
Некоторые ранние записи BUILD описывают предыдущие этапы; актуальный обзор
возможностей приведён выше и в CONTEXT.

## Статус

Согласно исходной документации, аппаратный H.264, касания и reconnect
проверены на Lenovo TB-X606F. Длительный soak/reconnect-тест и дальнейшая
политика восстановления видеопотока остаются в плане. При публикации
сборка и испытания не повторялись; соответствие APK снимку исходников
не проверено.
