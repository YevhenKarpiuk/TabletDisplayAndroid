# TabletDisplayAndroid

Android-приёмник для использования планшета как дополнительного монитора Windows 11 по USB/ADB.

- Парный проект: `C:\Users\ka\Documents\prog\c-plus-plus\TabletDisplayWindows`.
- Устройство MVP: Lenovo TB-X606F, Android 10, 1920×1200/1200×1920@60. `DISPLAY_INFO` передаёт физическое разрешение панели и пределы AVC-декодера; Windows выбирает режим = разрешение планшета / 1–4.
- Стек: Kotlin, Android SDK 36, minSdk 26, Gradle 9.2.1, JDK 17, `SurfaceView`, `MediaCodec`.
- Сеть: USB — `127.0.0.1:27183` через `adb forward`; Wi-Fi — TCP `27185` на всех интерфейсах, NSD `_tabletdisplay._tcp` (TXT `id`), сопряжение с диалогом кода, AES-256-GCM (`wifi/`, PROTOCOL.md §10). Один сеанс одновременно, остальным `HELLO_ACK BUSY`. Ключи сопряжения — в приватных SharedPreferences.
- Команды: `build.cmd`, `test.cmd`, `release.cmd`; release APK 0.3.0 — `dist\TabletDisplayAndroid-0.3.0.apk`, опубликован в `prebuilt/` и GitHub Releases.
- Готово: loopback server/relisten, strict parser/session, AVC MediaCodec → Surface с немедленным выводом на ближайшей развёртке, чёрный экран вне streaming, экран не гаснет только во время streaming, асинхронная bounded-очередь `DOWN/MOVE/UP/CANCEL` по тому же TCP-сеансу.
- Lenovo TB-X606F: виртуальный дисплей 1280×800@60 декодируется аппаратным `OMX.MTK.VIDEO.DECODER.AVC`; проверены tap/drag, разрыв соединения и автоматическое восстановление Host.
- На TB-X606F отказ от будущих render timestamps устранил накопление рассинхронизации часов: decoder→display снизился примерно с 69–74 до 33–45 мс, Surface queue — с 3 до 2 кадров.
- Release 0.1.2 подписывается локальным ключом из `signing` (каталог исключён из Git); launcher icon находится в mipmap-ресурсах.
- Восстановление: ошибка или остановка вывода декодера → перезапуск codec, `REQUEST_KEYFRAME`, пропуск кадров до IDR; вывод ждётся не дольше одного кадра; codec освобождается до уничтожения Surface; ориентация: Windows шлёт `ORIENTATION_POLICY` (§5.10) — `FOLLOW_SENSOR` даёт `FULL_SENSOR` и перезапуск сервера при повороте (новый `DISPLAY_INFO`), `LOCK` или его отсутствие фиксирует ориентацию `VIDEO_CONFIG`; между сеансами `FULL_SENSOR`; при переполнении теряются только MOVE; fatal `ERROR` отправляется и показывается на экране.
- `DISPLAY_INFO.maxAvc*` — верхние границы декодера по осям (`supportedWidths/Heights.upper`); `isSizeSupported` не используется — TB-X606F объявляет 1920×1088, но транслирует свои 1920×1200; `DISCONTINUITY` пересоздаёт codec вместо `flush()` (асинхронный режим присылает устаревшие индексы буферов).
- Далее: проверка восстановления keyframe на TB-X606F и длительный reconnect/soak-тест.
- Подробный план: [doc/IMPLEMENTATION_PLAN.md](doc/IMPLEMENTATION_PLAN.md).
