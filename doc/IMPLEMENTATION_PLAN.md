# План реализации TabletDisplayAndroid

> Статус 2026-08-14: виртуальный IddCx-дисплей аппаратно декодируется в ландшафтном 1280×800 и портретном 800×1280 режимах @60; реализованы sensor rotation, полукадровый low-latency render pacing и асинхронная передача single-touch. Следующий шаг — длительный reconnect/soak-тест, затем восстановление keyframe и политика переполнения очередей.

## 1. Цель и границы MVP

Создать Android-приложение, которое по USB/ADB принимает H.264-поток виртуального монитора Windows, аппаратно декодирует его непосредственно в `Surface` и отправляет touch-события обратно Windows.

MVP должен обеспечивать:

- Android-планшет с аппаратным декодером H.264/AVC;
- один Windows-сеанс;
- 1280×800/1920×1200 и 800×1280/1200×1920 при 30/60 FPS, если режим поддерживает устройство;
- TCP-сервер только на loopback-порту `27183`, доступный Windows через `adb forward`;
- полноэкранный вывод с корректным aspect ratio;
- single-touch, преобразуемый Windows-приложением в мышь;
- автоматическое ожидание следующего соединения после разрыва;
- отсутствие записи видеопотока и кадров в постоянное хранилище.

В MVP не входят: Wi-Fi, собственный USB Accessory/WinUSB transport, HEVC, звук, клавиатура, clipboard, stylus, полноценный multitouch, управление несколькими Windows-хостами.

## 2. Целевая архитектура

```text
Windows host
    ↓ USB / adb forward / TCP
LoopbackServer
    ↓ framed binary messages
SessionController
    ├─ protocol parser + heartbeat
    ├─ VIDEO_CONFIG / VIDEO_FRAME
    └─ TOUCH / REQUEST_KEYFRAME
             ↓
H264Decoder (MediaCodec)
             ↓ directly to Surface
DisplaySurface / SurfaceView

MotionEvent → TouchSender → same TCP session → Windows
```

Основные правила:

- сетевой reader никогда не блокирует UI thread;
- compressed video queue ограничена, устаревшие кадры отбрасываются;
- MediaCodec выводит напрямую в `Surface`, без Bitmap и копирования через Kotlin;
- каждый reconnect создаёт новую session и требует `VIDEO_CONFIG` + IDR;
- UI и decoder lifecycle синхронизированы с жизненным циклом `Surface`.

## 3. Предлагаемая структура проекта

```text
TabletDisplayAndroid/
├─ app/
│  └─ src/
│     ├─ main/java/com/tabletdisplay/
│     │  ├─ MainActivity.kt
│     │  ├─ ui/DisplaySurface.kt
│     │  ├─ session/SessionController.kt
│     │  ├─ transport/LoopbackServer.kt
│     │  ├─ protocol/
│     │  ├─ video/H264Decoder.kt
│     │  ├─ input/TouchSender.kt
│     │  └─ diagnostics/SessionStats.kt
│     ├─ test/
│     └─ androidTest/
├─ doc/
│  ├─ IMPLEMENTATION_PLAN.md
│  └─ PROTOCOL.md
├─ .vscode/
├─ AGENTS.md
├─ CLAUDE.md
└─ CONTEXT.md
```

Для MVP достаточно одного `app` module. Выносить protocol/transport в отдельные Gradle modules следует только при появлении реальной потребности в повторном использовании или усложнении тестов.

## 4. Этапы реализации

### Этап 0. Инструменты и каркас приложения

1. Проверить JDK, Android SDK, Platform Tools и Gradle wrapper; не зависеть от Android Studio для сборки.
2. Создать Kotlin Android application с minSdk, выбранным по реальному целевому планшету; compileSdk/targetSdk зафиксировать в `doc/BUILD.md`.
3. Настроить Gradle wrapper, version catalog при необходимости, Debug/Release и lint.
4. Добавить VS Code tasks для `assembleDebug`, unit tests, lint, `adb install -r`, запуска activity и чтения фильтрованных логов.
5. Выбрать простой UI без тяжёлой навигации: экран ожидания, экран подключения/видео и компактная диагностическая панель.
6. Добавить coroutine scopes с явным владением и отменой по lifecycle.

Критерий готовности: приложение собирается командой Gradle, устанавливается через ADB, запускается и проходит unit tests/lint без Android Studio.

### Этап 1. Спецификация протокола и parser

1. Создать копию согласованного `doc/PROTOCOL.md` из Windows-проекта.
2. Зафиксировать byte order, common header, лимиты payload и правила версий.
3. Реализовать типы сообщений MVP: `HELLO`, `HELLO_ACK`, `DISPLAY_INFO`, `VIDEO_CONFIG`, `VIDEO_FRAME`, `REQUEST_KEYFRAME`, `TOUCH`, `PING`, `PONG`, `ERROR`.
4. Реализовать incremental reader поверх buffered socket input: `readFully`, EOF, timeout и частичные пакеты.
5. Проверять magic/version/type/length до allocation; неизвестные optional-типы пропускать только согласно спецификации.
6. Добавить encoder сообщений для ответов и touch.
7. Перенести Windows golden vectors в Kotlin tests и требовать побитового совпадения.

Критерий готовности: parser корректно разбирает данные при любом разбиении TCP-потока, отклоняет oversized/malformed packets и совпадает с Windows golden vectors.

### Этап 2. Loopback transport и session state machine

1. Поднять `ServerSocket` на `127.0.0.1:27183`, не слушать `0.0.0.0` и Wi-Fi-интерфейсы.
2. Принимать только одну активную session; для повторного клиента определить явную политику замены или отказа.
3. Выполнить handshake: версия, capabilities decoder, размеры физического экрана, DPI, поддерживаемые AVC profile/level и maximum input size.
4. Реализовать состояния `Idle → Listening → Handshaking → Streaming → Reconnecting/Listening → Stopped`.
5. Разнести reader, writer и heartbeat по управляемым coroutine jobs; сериализовать все исходящие пакеты через одну очередь.
6. Ввести timeouts и очистку ресурсов при EOF, protocol error, background/stop и уничтожении activity.
7. После разрыва вернуться к `Listening` без завершения приложения.

Критерий готовности: тестовый Windows-клиент подключается, обменивается ping/pong, отключается и повторно подключается сотни раз без утечки socket, thread или coroutine.

### Этап 3. Surface и полноэкранный режим

1. Реализовать `SurfaceView` с `SurfaceHolder.Callback` и явными событиями create/change/destroy.
2. Перевести приложение в immersive fullscreen, учитывать системные gestures/cutouts без искажения изображения.
3. Сохранять исходное aspect ratio; letterbox/pillarbox заполнять нейтральным чёрным цветом.
4. Вычислять фактический display rectangle внутри view — он понадобится для touch mapping.
5. Удерживать экран включённым только во время активного streaming session.
6. Зафиксировать ориентацию для MVP либо согласовать reconnect/reconfigure при её изменении; не допускать скрытого растяжения кадра.

Критерий готовности: тестовая Surface корректно переживает background/foreground, блокировку, пересоздание activity и смену системных панелей.

### Этап 4. Аппаратное H.264-декодирование

1. До handshake запросить `MediaCodecList` и выбрать decoder `video/avc`, предпочитая аппаратный codec; логировать выбранное имя.
2. Настроить `MediaFormat` по `VIDEO_CONFIG`: width, height, profile/level, codec-specific data или Annex B configuration согласно протоколу.
3. Конфигурировать decoder с `Surface`, не использовать byte-output/Bitmap path.
4. Подачу encoded access units выполнять с presentation timestamp из Windows.
5. Ограничить очередь compressed frames; при перегрузке отбросить старые non-IDR, очистить decoder и отправить `REQUEST_KEYFRAME`.
6. После codec error, surface loss, reconnect или смены разрешения полностью пересоздать MediaCodec и ждать новый IDR.
7. Обработать `INFO_OUTPUT_FORMAT_CHANGED`, EOS только при штатном завершении и device-specific codec exceptions.
8. Не объединять произвольно NAL units: границы access unit определяет wire protocol.

Критерий готовности: prerecorded streams всех MVP-режимов воспроизводятся аппаратно, без постоянных артефактов; остановка/перезапуск decoder не требует перезапуска приложения.

### Этап 5. Сквозной video pipeline

1. Связать socket reader с decoder через bounded channel, не помещая весь сетевой поток в память.
2. Передавать только payload текущего `VIDEO_FRAME`, освобождая buffer после queue/decode.
3. Собирать frame number, receive timestamp, queue delay и presentation time для диагностики.
4. При `VIDEO_CONFIG` атомарно остановить старый decoder, очистить очередь, создать новый format и дождаться IDR.
5. На потерю последовательности реагировать запросом keyframe, а не бесконечным показом повреждённого потока.
6. Показывать пользователю состояния «Ожидание USB», «Подключение», «Нет поддерживаемого декодера», «Ошибка протокола» без технического шума.

Критерий готовности: Windows synthetic sender и затем реальный host дают стабильное изображение 60 минут; reconnect и mode change восстанавливают видео автоматически.

### Этап 6. Touch и координаты

1. Перехватывать `MotionEvent` только внутри display rectangle; касания в letterbox не отправлять.
2. Для MVP отслеживать один primary pointer и действия down/move/up/cancel.
3. Нормализовать координаты в диапазон `[0, 1]` относительно видимой области изображения, независимо от разрешения Android view.
4. Добавлять pointer id, monotonic timestamp и sequence; move-события coalesce/throttle до согласованной разумной частоты.
5. Передавать `CANCEL/UP` при потере фокуса, уничтожении Surface или session disconnect.
6. Не интерпретировать системные edge gestures как ввод удалённого дисплея.
7. После MVP расширить протокол для multitouch, pressure, tool type и stylus.

Критерий готовности: палец точно управляет всей областью виртуального Windows-монитора при letterbox/pillarbox и не оставляет на Windows зажатую кнопку после разрыва.

### Этап 7. Lifecycle, энергопотребление и reconnect

1. Явно определить работу при свёртывании: остановить decoder/stream и закрыть session либо использовать foreground service только если это действительно понадобится.
2. На `Surface` loss остановить вывод и запросить новую конфигурацию/IDR после восстановления.
3. Обработать USB disconnect косвенно через TCP EOF; не требовать USB Host permission для ADB-варианта.
4. Не держать wake lock в `Idle/Listening`; во время streaming использовать минимально необходимый механизм `keepScreenOn`.
5. Защититься от быстрых start/stop/reconnect с помощью единственного владельца session state.
6. Проверить Doze, screen off/on, lock/unlock и activity recreation.

Критерий готовности: приложение возвращается в корректное состояние после всех lifecycle-сценариев, без duplicate server и зависшего codec.

### Этап 8. Диагностика и настройки

1. Добавить структурированные события: session id, protocol state, codec, resolution, FPS, bitrate, dropped frames и reconnect count.
2. Не логировать H.264 payload, кадры, содержимое экрана или пользовательские touch-координаты в обычном режиме.
3. Добавить debug overlay, отключённый по умолчанию: receive FPS, render FPS, queue depth, bitrate и приблизительная latency.
4. Для MVP оставить минимум настроек: показывать overlay, фиксировать ориентацию, возможно выбирать prefer-low-latency.
5. Экспорт диагностического файла делать только по явному действию пользователя; исключить чувствительные данные.

Критерий готовности: по логам можно отличить проблемы ADB, handshake, decoder, surface и malformed stream без доступа к содержимому экрана.

### Этап 9. Тестирование и выпуск личного MVP

1. Добавить JVM unit tests для protocol, state machine, coordinate mapping и queue policy.
2. Добавить instrumented tests для Surface lifecycle, если они дают стабильный сигнал на целевом устройстве.
3. Создать debug APK и release APK с воспроизводимыми версиями Gradle/JDK.
4. Настроить R8 только после проверки MediaCodec/serialization; не оптимизировать преждевременно.
5. Описать установку APK, включение Developer options/USB debugging и подтверждение RSA-ключа компьютера.
6. Не запрашивать INTERNET permission, если loopback socket работает без него на выбранных API; если permission технически необходим, документировать причину и не открывать LAN listener.
7. Проверить release build на реальном планшете, а не только emulator.

Критерий готовности: APK устанавливается командой `adb install -r`, автоматически ожидает Windows host и выполняет полный MVP-сценарий без Android Studio.

## 5. Тестовая стратегия

### Unit tests

- protocol encode/decode и golden vectors;
- partial reads, EOF, oversized length, unknown type/version;
- session state transitions и повторные события;
- normalized coordinate mapping с letterbox/pillarbox;
- bounded queue/drop policy и keyframe recovery;
- touch coalescing и обязательный cancel/up.

### Интеграционные тесты

- localhost test client → Android protocol server;
- prerecorded Annex B streams → MediaCodec → Surface;
- Windows synthetic sender → ADB → Android;
- reconnect, resolution change и forced keyframe;
- malformed stream без падения приложения.

### Матрица ручной проверки

- целевой Android-планшет и минимум одно устройство с другим производителем/SoC, если доступно;
- USB 2.0 и USB 3.x кабели;
- landscape и выбранное поведение portrait;
- 1280×800/1920×1200 и 800×1280/1200×1920, 30/60 FPS;
- static text, scrolling, video, rapid window movement;
- home/recents, screen off/on, lock/unlock, activity recreation;
- USB unplug/replug, `adb kill-server`, Windows host restart;
- decoder error и повторный IDR.

## 6. Метрики MVP

- время от успешного handshake до первого отображённого кадра: целевое значение ≤ 2 секунд;
- приложение не добавляет неограниченную очередь; при перегрузке latency возвращается к норме после drop/keyframe;
- reconnect после восстановления Windows-соединения без перезапуска приложения;
- 60 минут streaming без crash, зависшего decoder или заметного роста памяти;
- render FPS соответствует согласованному режиму в пределах возможностей дисплея/decoder;
- touch mapping покрывает весь виртуальный монитор с ошибкой не более нескольких экранных пикселей после преобразования Windows.

## 7. Зависимости и порядок совместной разработки

1. Сначала согласовать `PROTOCOL.md` и побитовые golden vectors с Windows-проектом.
2. Реализовать Android server/parser и проверить Windows synthetic client.
3. Поднять Surface + MediaCodec на prerecorded H.264 до подключения реального драйвера.
4. Подключить Windows synthetic video sender, затем настоящий Media Foundation stream.
5. Добавить reconnect и mode change до touch, чтобы ввод строился на стабильной session model.
6. Любое изменение wire format одновременно обновляет обе спецификации и тестовые векторы.

## 8. Условия завершения MVP

MVP завершён, когда после запуска приложения планшет ожидает ADB-соединение, принимает поток Windows-виртуального монитора, аппаратно и полноэкранно отображает его с правильными пропорциями, отправляет single-touch обратно и самостоятельно возвращается к ожиданию/streaming после отключения и повторного подключения USB.
