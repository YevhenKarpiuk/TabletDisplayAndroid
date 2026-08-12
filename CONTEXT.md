# TabletDisplayAndroid

Android-часть системы, превращающей планшет в дополнительный монитор Windows 11 по USB.

- Парный проект: `C:\Users\ka\Documents\c-plus-plus\TabletDisplayWindows`.
- Назначение: принимать H.264-поток виртуального дисплея Windows, аппаратно декодировать его на экран и возвращать touch-события.
- MVP: один планшет, 1280×800 и 1920×1200, 30/60 FPS, H.264/AVC, полноэкранный вывод, single-touch как мышь.
- Стек: Kotlin, Android SDK, Gradle, MediaCodec, SurfaceView/Surface, coroutines, TCP sockets, ADB.
- Транспорт MVP: приложение слушает `127.0.0.1:27183`; Windows создаёт `adb forward tcp:27183 tcp:27183` и подключается к нему.
- Контракт с Windows: версионированный бинарный протокол; эталонная спецификация должна храниться в `doc/PROTOCOL.md` и синхронно отражаться в обоих проектах.
- Приоритеты: низкая задержка, bounded buffers, корректный reconnect, отсутствие копирования декодированных кадров через Kotlin/Java.
- План: [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).

Статус: wire-протокол v1 и parser покрыты golden-тестами. Activity запускает loopback-сервер, выполняет handshake, сообщает параметры display и отвечает PONG; проверено по USB на Lenovo TB-X606F. MediaCodec streaming и touch ещё не подключены.
