# CTV House Android SDK

Русская версия: [README.ru.md](README.ru.md).

TriggerRoll for apps built on a Media3 content player: the host reports an opportunity, and a
VAST creative is shown over the video. Two formats are supported — **video** (linear
`MediaFile`, audio-only included) and **banner** (companion still image). Two launchers turn a
tag URL into a show: `PauseRollAd` for a pause in playback, `SwitchRollAd` for an action the
viewer took in the app. Runs on phones and on Android TV.

- Integration guide: **[docs/INTEGRATION.md](docs/INTEGRATION.md)**
- What it does to your app: **[docs/FAQ.md](docs/FAQ.md)**
- What a VAST response must carry: **[docs/VAST.md](docs/VAST.md)**
- Release history: [CHANGELOG.md](CHANGELOG.md)
- Distribution: [JitPack](https://jitpack.io/#CTV-House/android-sdk)
- Licence: [LICENSE](LICENSE) — non-commercial use; commercial rights belong to the copyright
  holder

## Requirements

| | |
|---|---|
| minSdk | 21 |
| JVM target | 11 |
| Media3 | `1.2+`, provided by the host (`compileOnly` in the AAR) |
| Permissions | shipped by the AAR: `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` |
| Language | Kotlin or Java |

## Install

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

## Quick start

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

Pass `setSoundEnabled(false)` (or `soundEnabled = false` to the constructor) to play creatives
silently, without touching the audio focus of other apps.

`detach()` is required when the screen goes away (`onDestroy`): it stops the library's threads
and releases the host views. The instance is terminal afterwards — create a new one for the
next screen. `trigger` / `close` on the same instance cover every pause while the screen lives.

Public API: `com.ctvhouse.sdk.format` (`TriggerRoll`), `com.ctvhouse.sdk.manual` (`PauseRollAd`,
`SwitchRollAd` — both from a tag URL), `com.ctvhouse.sdk.SdkVersion`. Everything else is
internal.

A full Activity example, the two creative formats, events, and limits are in the
[integration guide](docs/INTEGRATION.md).

## Build

```bash
./gradlew testDebugUnitTest lint assembleRelease
```

## Licence

Use is permitted for non-commercial purposes: review, evaluation, education, development, and
testing. Commercial use is reserved to the copyright holder, or requires a separate written
agreement. Full text in [LICENSE](LICENSE).
