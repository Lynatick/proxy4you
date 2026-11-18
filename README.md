# Proxy4You → YouTube Proxy для Android TV

> Репозиторий переписан: приложение собрано заново под Android TV (Xiaomi Mi Stick 4K).
> Прежняя Flutter/Hiddify-версия сохранена в ветке `legacy-hiddify-5.5.5`.
> Файл `LICENSE.md` относится к прежнему коду; у этого приложения свой состав
> зависимостей — сторонний код только hev-socks5-tunnel (MIT, см. `app/src/main/cpp/hev/LICENSE`).

# YouTube Proxy для Android TV

Нативное приложение под Android TV (проверялось на Xiaomi Mi Stick 4K): поднимает
VPN и пускает **только выбранные приложения** через прокси-ноды, экспортированные
из macOS-приложения.

```
Dist/YouTubeProxy-TV-1.0.apk     arm64-v8a, minSdk 23, targetSdk 30
```

## Установка

1. На телевизоре включить отладку: «Настройки → Система → Сведения → 7 раз по
   «Сборка» → Для разработчиков → Отладка по сети».
2. С Mac:

```
adb connect <ip-телевизора>:5555
adb install -r Dist/YouTubeProxy-TV-1.0.apk
```

Либо скопировать APK на флешку и открыть файловым менеджером (`Downloader`).

## Как работает

```
приложение → TUN (VpnService) → hev-socks5-tunnel → 127.0.0.1:SOCKS5
                                                        │
                                    наш SOCKS5-сервер (protect)
                                                        │
                                          удалённая нода (HTTP/SOCKS5)
```

Ключевой момент: `hev-socks5-tunnel` умеет только SOCKS5 и создаёт исходящие
соединения сам — внутри VPN они попали бы обратно в туннель. Поэтому соединения
к нодам открывает наш собственный SOCKS5-сервер, и каждое проходит через
`VpnService.protect()`.

## Только выбранные приложения

По умолчанию включены приложения YouTube (`com.google.android.youtube.tv`).
Кнопка «Приложения для VPN» открывает список установленных приложений с
отметками — выбранные попадают в `addAllowedApplication`, остальной трафик
телевизора идёт напрямую. Пустой выбор означает «все приложения».

## Ноды

`app/src/main/assets/nodes.txt` — экспорт из macOS-приложения в формате
`socks5://host:port` и `http://host:port`, отсортированный по задержке.
Обновить: пересобрать экспорт и скопировать файл.

## Сборка

Нужны JDK 25, Android SDK (platform 36, build-tools 36, NDK 28.2.13676358):

```
export JAVA_HOME=<jdk-25>
export ANDROID_HOME=$HOME/Library/Android/sdk
export GRADLE_USER_HOME=$PWD/.gradle-home
gradle :app:assembleRelease
```

Ключ подписи создаётся локально:

```
keytool -genkeypair -keystore keystore/mistick.jks -storepass mistick123 \
  -keypass mistick123 -alias mistick -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=MiStick Proxy, OU=Dev, O=Local, C=RU"
```

## Ограничения

- Только arm64-v8a (Mi Stick 4K подходит).
- UDP-трафик проксируется по TCP и на бесплатных нодах обычно не работает;
  YouTube переключится на TCP.
- Ноды публичные: скорость и живучесть ограничены, трафик видит владелец ноды.
- `targetSdk 30` — для сайдлоада; для Google Play потребуется 34+ и
  foregroundServiceType.

## Сторонний код

`app/src/main/cpp/hev` — hev-socks5-tunnel 2.20.0 (MIT, см. LICENSE в каталоге).
