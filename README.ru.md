# CTV House Android SDK

English version: [README.md](README.md).

TriggerRoll для приложений с контентным плеером Media3: хост сообщает о возможности, и поверх
видео показывается VAST-креатив. Поддерживаются два формата — **video** (linear
`MediaFile`, включая audio-only) и **banner** (companion-изображение). Показ из tag URL
запускают три лончера: `PauseRollAd` — на паузу контента, `SwitchRollAd` — на действие зрителя
в приложении, `StartRollAd` — на запуск приложения. Работает на телефонах и на Android TV.

- Руководство по интеграции: **[docs/INTEGRATION.ru.md](docs/INTEGRATION.ru.md)**
- Влияние на приложение: **[docs/FAQ.ru.md](docs/FAQ.ru.md)**
- Требования к VAST-ответу: **[docs/VAST.ru.md](docs/VAST.ru.md)**
- История версий: [CHANGELOG.ru.md](CHANGELOG.ru.md)
- Дистрибуция: [JitPack](https://jitpack.io/#CTV-House/android-sdk)
- Лицензия: [LICENSE](LICENSE) — некоммерческое использование; коммерческое право
  принадлежит правообладателю

## Требования

| | |
|---|---|
| minSdk | 21 |
| JVM target | 11 |
| Media3 | `1.2+`, предоставляет хост (`compileOnly` в AAR) |
| Разрешения | объявляет сам AAR: `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` |
| Язык | Kotlin или Java |

## Подключение

```kotlin
// settings.gradle.kts
maven("https://jitpack.io")

// app/build.gradle.kts
dependencies {
    implementation("com.github.CTV-House:android-sdk:1.2.0")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
}
```

## Быстрый старт

```kotlin
import com.ctvhouse.sdk.format.TriggerRoll
import com.ctvhouse.sdk.manual.PauseRollAd

pauseRoll = PauseRollAd(overlayContainer)
    .setTagUrl(vastTagUrl)
    .setListener(object : TriggerRoll.Listener {
        override fun onOpen() { }
        override fun onImpression(urls: List<String>) { }
        override fun onProgress(offset: TriggerRoll.ProgressOffset, urls: List<String>) { }
    })
    .attach()
pauseRoll.trigger("pause")
```

Через `setSoundEnabled(false)` (или `soundEnabled = false` в конструкторе) креативы играют
без звука и не забирают аудиофокус у других приложений.

`detach()` обязателен при уничтожении экрана (`onDestroy`): он останавливает потоки библиотеки
и отпускает view хоста. Экземпляр после `detach()` терминальный — для следующего экрана
создайте новый. Пока экран жив, каждый pause закрывается парой `trigger` / `close` на том же
экземпляре.

Публичный API: `com.ctvhouse.sdk.format` (`TriggerRoll`), `com.ctvhouse.sdk.manual`
(`PauseRollAd`, `SwitchRollAd`, `StartRollAd` — все по tag URL), `com.ctvhouse.sdk.SdkVersion`.
Остальное — внутреннее.

Полный пример Activity, оба формата креатива, события и ограничения — в
[руководстве по интеграции](docs/INTEGRATION.ru.md).

## Сборка

```bash
./gradlew testDebugUnitTest lint assembleRelease
```

## Лицензия

Использование разрешено в некоммерческих целях: ознакомление, оценка, обучение, разработка и
тестирование. Коммерческое использование — только правообладателем либо по отдельному
письменному соглашению с ним. Полный текст — в [LICENSE](LICENSE).
