# Новые подписки (нет в goida) — проверка YouTube, 2026-11-21

Источники (свежие ноды, которых нет в goida; goida — 27 669 уникальных server:port):

| Источник | Ссылок | Новых (нет в goida) | Рабочих для YouTube |
|---|---|---|---|
| 0xRadikal/Free-v2ray-Configs | 11 577 | 3 284 | 2 |
| Argh94/Proxy-List | 2 573 | 130 | 2 |
| mahdibland/ShadowsocksAggregator (Eternity) | 200 | 4 | 0 |
| roosterkid/openproxylist (V2RAY_RAW) | 130 | 1 | 0 |
| Au1rxx/free-vpn-subscriptions | — (base64 не распознан) | 0 | 0 |

Проверено 3 297 валидных нод (16 отброшены sing-box check: invalid public_key).
Цель проверки: https://www.youtube.com/generate_204, таймаут 4 с, 80 потоков.
Результат: 4 ноды отвечают 204 — 3 уникальных адреса.

Рабочие ноды:

| Адрес | Тип | Задержка (Mac) | Источник |
|---|---|---|---|
| 195.133.5.196:18443 | shadowsocks aes-256-gcm | 264 / 282 мс | Argh94/Proxy-List, 0xRadikal |
| 144.31.90.235:8445 | vless + reality | 505 мс | 0xRadikal |
| 188.225.35.83:443 | vless | 827 мс | 0xRadikal |

Проверка на Mi Stick (Android 14, armeabi-v7a):

| Ядро | YouTube | ChatGPT | Telegram | GitHub | WhatsApp |
|---|---|---|---|---|---|
| Xray | OK 1058 | OK 1052 | OK 990 | OK 1177 | OK 860 |
| sing-box | OK 828 | OK 918 | OK 923 | OK 1038 | OK 861 |

Файлы: Dist/working-youtube-nodes-2026-11-21.txt (4 share-ссылки),
Dist/working-youtube-subscription-2026-11-21.json (JSON-подписка из 4 нод).
