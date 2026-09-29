# Bebragram

Telegram с поддержкой сети Tor на базе NagramX.

Чтобы подключиться, откройте **Настройки → Сеть Tor** и включите Tor-прокси. Приложение загрузит мосты с GitHub, проверит их и подключит Telegram через Tor. Если мост перестанет работать, приложение попробует обновить список и подключиться снова.

При включённом VPN Tor ждёт его отключения. Это поведение меняется переключателем в разделе прокси.

Без включённого Tor-прокси Telegram работает как обычно. Для сборки из исходников нужны Android SDK, ключ подписи и действительные Telegram API ID и hash.

Исходный проект: [NagramX](https://github.com/risin42/NagramX). Список мостов: [Tor Bridges Collector](https://github.com/Delta-Kronecker/Tor-Bridges-Collector). Лицензия: [GPLv3](LICENSE).
