# Integration guide

Русская версия: [INTEGRATION.ru.md](INTEGRATION.ru.md).
Questions about the impact on your app: [FAQ.md](FAQ.md).
What the ad server response must carry: [VAST.md](VAST.md).

The library shows a VAST creative over your content player when the host reports an
opportunity — a pause in playback (`PauseRollAd`, §3), an action the viewer took in the app
(`SwitchRollAd`) or the app opening (`StartRollAd`), both in §10. Two creative formats are
supported, both served through the same entry point:

| Format | Creative | Behaviour |
|---|---|---|
| **Video** | VAST `Linear` with a progressive `MediaFile` | plays with sound, dismisses itself when it ends |
| **Banner** | VAST `Companion` with a `StaticResource` image | stays on screen until skip or content resume |

Which one appears is decided by the VAST response, not by the host. A companion
`StaticResource` is shown as a banner. If there is none, a playable `MediaFile` is shown as
video. Ad-server PauseRoll tags often ship both — a Linear leftover from the preroll template
and a fullscreen companion; the still is the pause creative. An audio-only `MediaFile` is
played as a video creative without a picture.

The host does not have to care which format is on screen — `onOpen()` and `onClose()`
behave the same for both. The difference shows up in the tracking callbacks (§11) and in whether
the creative dismisses itself.

---

## 1. Requirements

| | |
|---|---|
| minSdk | 21 |
| JVM target | 11 |
| Media3 | `1.2+`, provided by the host; verified on 1.3.1 |
| Permissions | declared by the AAR: `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` |
| Language | Kotlin or Java |

Media3 is declared `compileOnly`, so the host picks the version. The whole app must stay on
one Media3 line. Some TV ART builds never dispatch Java default methods on
`Player.Listener` — a Kotlin listener then dies with `AbstractMethodError`. Extend
`PlayerCallbacks` instead.

## 2. Install

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

R8 rules ship inside the AAR — nothing to add.

## 3. Wire it up

One instance serves one screen. Create it once the overlay container exists, tell it when
there is an opportunity, and release it when the screen is destroyed. The content player is
not an SDK input: slot `onClose` reads your `player` field, so the same `PauseRollAd` and listener
work across `onStart` / `onStop` as you replace the ExoPlayer. The snippet uses `PauseRollAd`,
the tag-URL launcher. The format itself is `TriggerRoll`; a host that already has markup
implements `TriggerRoll.Controller` instead.

```kotlin
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var pauseRoll: PauseRollAd? = null
    private var contentHadPlayback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pauseRoll = PauseRollAd(binding.root)
            .setTagUrl(VAST_TAG_URL)
            .setRequestTimeoutMs(10_000)
            .setSkipOffsetSeconds(5)
            .setMarkingTemplate("AD \${ERID}")
            .setSkipCountdownTemplate("Skip in \${SECONDS}")
            .setSkipTemplate("Skip ›")
            .setSoundEnabled(true)
            .setControlsPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.BOTTOM)
            .setMarkingPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.TOP)
            .setSkipPosition(Overlay.ControlsHorizontal.RIGHT, Overlay.ControlsVertical.TOP)
            .setLogoVisible(true)
            .setInfoVisible(true)
            .setPauseVisible(true)
            .setMuteVisible(true)
            .setListener(adListener)
            .attach()
    }

    override fun onStart() {
        super.onStart()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(CONTENT_URL))
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : PlayerCallbacks() {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                contentHadPlayback = true
                pauseRoll?.close()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && contentHadPlayback) {
                    pauseRoll?.trigger("pause")
                }
            }
        })
    }

    override fun onStop() {
        pauseRoll?.close()
        contentHadPlayback = false
        binding.playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        pauseRoll?.detach()
        pauseRoll = null
        super.onDestroy()
    }

    private val adListener = object : TriggerRoll.Listener {
        override fun onOpen() {
            binding.playerView.useController = false
            binding.playerView.hideController()
        }

        override fun onClose() {
            // close() from onStop lands here too — resuming then would play in the background.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.playerView.requestFocus()
            player?.playWhenReady = true
        }

        override fun onNoAd() = Unit
        override fun onError(message: String) = Log.w("TriggerRoll", message)
        override fun onSkip(urls: List<String>) = Log.i("TriggerRoll", "skip")
        override fun onImpression(urls: List<String>) = Log.i("TriggerRoll", "impression")
        override fun onStart(urls: List<String>) = Log.i("TriggerRoll", "video started")
    }
}
```

In Java the same listener needs only the callbacks you care about:

```java
pauseRoll = new PauseRollAd(rootView)
        .setTagUrl(VAST_TAG_URL)
        .setListener(new TriggerRoll.Listener() {
            @Override public void onOpen() { }
            @Override public void onImpression(@NonNull List<String> urls) { }
        })
        .attach();
pauseRoll.trigger("pause");
```

`binding.root` is the container the overlay is added to — normally the root `FrameLayout`
that already holds the player view. The overlay is inserted hidden at `attach()` and brought
to the front right before a show, so views the host adds later never cover the creative.
The library does not watch or write the content player. Call `trigger` when you
want a show (typically a pause after content has played) and `close` when that
window is gone (typically play). A later seek can use the same pair; the SDK does not
implement seek itself.

### Silent playback

Sound is configured on the slot, either as a constructor argument or with a setter:

```kotlin
PauseRollAd(binding.root)
    .setSoundEnabled(false)
```

```java
new PauseRollAd(rootView).setSoundEnabled(false);
```

The same value can be passed as the constructor's second argument:
`PauseRollAd(binding.root, soundEnabled = false)`. Default is `true`. With `false`, every creative plays at zero volume and the library does not
request audio focus, so music or another app playing in the background is left untouched.
The mute control is hidden. The starting silence is not reported as a VAST `mute` event — the
viewer did not do anything. Call it before the creative starts.

## 4. The video format

A `Linear` creative with a progressive `MediaFile` is played by a dedicated `ExoPlayer` on a
surface above your content. Selection order inside `MediaFiles`: progressive video
(`mp4` → `webm` → other), then progressive audio. HLS, DASH, and VPAID are never played, even
when the response carries nothing else. The container is taken from the MIME type, and from the
URL path when the MIME type is missing.

What the host sees:

- With sound enabled the ad requests audio focus, so other apps duck the same way they would
  for content. With `soundEnabled = false` neither happens.
- `onStart`, quartiles, `progress@offset`, `onMute` / `onUnmute`, `onPause` / `onResume`.
- The creative dismisses itself at the end: `complete`, `closeLinear`, VAST `close`, then
  slot `onClose()`. With `setDismissOnCreativeEnd(false)` the last frame stays on screen after
  `complete` and `closeLinear`, and the show ends on skip or on your `close()`.
- Quartiles and progress follow the creative's playback position, not wall-clock time, so
  buffering does not push events forward.

If playback fails, the library reports `onVastError` plus `onError` and hides the overlay.
The content player is left as it was; a host that resumes in slot `onClose` will continue
playback after that hide.

## 5. The banner format

A companion `StaticResource` image is shown full-screen, scaled with `FIT_CENTER`. When the same
VAST also has a Linear `MediaFile`, the companion still wins: that is the pause creative. Video
is used only when there is no companion, or when the image fails to download.

The image keeps its alpha channel, and nothing of ours is painted where the creative is not, so a
transparent PNG shows the host's content through it. The same goes for the letterbox around a
creative of another aspect ratio. `setBackdropVisible(true)` puts a black fill under the creative
instead, for a placement that has to cover the content. The controls keep their own scrim either
way.

If Linear also has a dedicated audio `MediaFile` (`audio/*` or a known audio extension), that
file plays under the still, without a video surface. A video `MediaFile` is never used as a
soundtrack: no audio file means a silent banner. Mute and pause appear while that audio plays.
When it ends, `complete` and `closeLinear` are sent and mute/pause go away. If the audio fails,
the image stays; the host gets `onError`.

A still has no playback that can end, so its time on screen ends it instead:

- the soundtrack, when the response has one — its end is the end of the creative;
- otherwise the `Duration` of the `Linear` in the same response;
- otherwise `setBannerDurationSeconds(n)`, which is `0` — no timer — unless you set it.

That is what closes the still by default. With `setDismissOnCreativeEnd(false)` nothing above
takes the image down, and it waits for skip or for your `close()`.

Worth deciding per placement: on a pause screen, a slot `onClose()` that resumes content would
resume it while the viewer is still on pause. Pass `setDismissOnCreativeEnd(false)` there, the way
the demo does, and let the viewer end the show.

What the host sees:

- `onImpression`, `creativeView`, `loaded`. `start`, quartiles, `progress` and `complete` only
  while a soundtrack is playing.
- The show ends by itself when the time above runs out: VAST `close`, then slot `onClose()`.
  A still with nothing to time waits. Skip is VAST `onSkip(urls)` then slot `onClose()`;
  content resume is VAST `close` then slot `onClose()`.
- The image body is capped at 8 MB and decoded downsampled to at most 4 MPx.
- If the image cannot be downloaded and a Linear `MediaFile` is present, the library falls
  back to video. If there is no video either, nothing is shown: no overlay, no impression,
  `onNoAd()`.

## 6. Skip

The skip button appears immediately and becomes active after the offset, counting down in the
label. The offset is resolved in this order:

1. `setSkipOffsetSeconds(n)` if the host set it;
2. the absolute `skipoffset` of the VAST `Linear` (a percentage `skipoffset` is ignored);
3. 5 seconds.

Skipping fires the VAST `skip` trackers and `onSkip(urls)`. The library does not resume
content. Do that in slot `onClose()`: it also runs when the video creative ends and when the
host calls `close()`. `onNoAd` has no matching `onClose` — resume there too if a miss should
not leave the stream paused.

## 7. Chrome

Three pieces sit independently, each with its own corner (horizontal × vertical):

```kotlin
.setControlsPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.BOTTOM)
.setMarkingPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.TOP)
.setSkipPosition(Overlay.ControlsHorizontal.RIGHT, Overlay.ControlsVertical.TOP)
```

| Piece | Default | Contents |
|---|---|---|
| playback group | left × bottom | info, mute, pause |
| marking | left × top | brand mark + ad-marking chip |
| skip | right × top | landing chip + skip countdown / skip |

Mute and pause appear for a linear creative, and while a soundtrack plays under a banner.
Info and the landing chip appear when the creative has an http(s) `ClickThrough`. A banner keeps
marking, skip, info and landing when a landing URL exists. Chrome is shown together with the
creative, not while the media is still buffering.
Tapping mute or pause fires the VAST `mute` / `unmute` / `pause` / `resume` trackers the same
way a player event would. Tapping info opens a QR of the ClickThrough and pings `ClickTracking`;
the landing chip (`Перейти`, left of skip) opens the same URL in the device browser (not the
host), pings `ClickTracking`, then ends the show — VAST `close` and slot `onClose()`.

The chrome is built for a D-pad: the overlay itself does not take focus, skip stays in the
chain during the countdown, and a focused control turns white. From pause, the remote can
reach the landing chip and skip even when they sit in another corner.

The brand mark sits in the ad-marking chip, to the left of the label. It is on by default;
`setLogoVisible(false)` hides the icon and leaves the marking text. Info, landing, pause and mute
can be hidden with `setInfoVisible` / `setLandingVisible` / `setPauseVisible` / `setMuteVisible`.

## 8. Configuration

Everything is a chainable setter that may be called before `attach()`, and every launcher exposes
the same set. Sound may also be passed to the constructor.

| Method | Default | Notes |
|---|---|---|
| constructor `soundEnabled` / `setSoundEnabled` | `true` | `false` plays creatives silently, no audio focus |
| `setTagUrl` | — | required before the first opportunity; macros are expanded on fetch (§9) |
| `setIfa` | platform ID | overrides the advertising ID the device reports; empty goes back to it |
| `setIp` | empty | client IP for `${IP}`; not visible from inside the app |
| `trigger` | — | host fires an opportunity (`reason` is for logs) |
| `close` | — | host ends it; drops an in-flight request |
| `setRequestTimeoutMs` | `10000` | tag and Wrapper hops |
| `setSkipOffsetSeconds` | VAST `skipoffset`, else `5` | |
| `setDismissOnCreativeEnd` | `true` | `false` holds the creative until skip or `close` |
| `setBannerDurationSeconds` | `0` | time on screen for a still the response does not time |
| `setControlsPosition` | left × bottom | playback group: info / mute / pause |
| `setMarkingPosition` | left × top | ad-marking chip |
| `setSkipPosition` | right × top | landing chip + skip chip |
| `setLogoVisible` | `true` | brand mark in the marking chip |
| `setBackdropVisible` | `false` | `true` paints a black fill under the creative |
| `setInfoVisible` | `true` | QR of the ClickThrough; still hidden without a landing URL |
| `setLandingVisible` | `true` | chip that opens the ClickThrough in the browser; still hidden without a landing URL |
| `setPauseVisible` | `true` | pause of the linear creative; still hidden for a banner |
| `setMuteVisible` | `true` | mute of the linear creative; still hidden for a banner or when sound is off |
| `setMarkingTemplate` | `РЕКЛАМА ${ERID}` | Russian ad-marking default |
| `setSkipCountdownTemplate` | `Пропустить через ${SECONDS}` | |
| `setSkipTemplate` | `Пропустить` | |
| `setLandingTemplate` | `Перейти` | label of the landing chip |
| `setDebugLogging` | `false` | process-wide; test builds only |
| `setListener` | `null` | slot window and VAST tracking |

The text defaults are Russian, and the marking one follows Russian ad-marking rules. Override
them for other markets. Placeholders: `${ERID}`, `${SECONDS}`.

## 9. Device and macros

The library builds one device snapshot per screen and fills macros from it — in the tag URL, in
every tracking pixel it fires, in the creative URL, and in the `ClickThrough`. A
pixel that arrives with `[IFA]` in it therefore reports the same device as the request that
earned it.

Three install-time permissions ship with the AAR, none of which prompts the viewer:
`INTERNET`, `ACCESS_NETWORK_STATE` (the connection type), and
`com.google.android.gms.permission.AD_ID` (reading the advertising ID on Android 13+).

The advertising ID is read from the Play Services identifier service, or from the
`Settings.Secure` pair on Amazon devices. There is no Play Services dependency in the artifact
and no dependency on it at runtime: a device with neither source simply has no ID. The lookup
runs on a background thread when `attach()` is called, so no host call ever waits for it.

An opted-out viewer, a device without an ID, and a host that removed the `AD_ID` permission all
produce the same result: `${IFA}` is empty and `${LIMITADTRACKING}` is `1`. `setIfa` overrides
the platform ID for a host that manages consent itself.

| Macro | Aliases | Value |
|---|---|---|
| `${IFA}` | | advertising ID, empty when unavailable |
| `${IFATYPE}` | | `gaid`, `afai`, or `host` when set by the host |
| `${LIMITADTRACKING}` | `${LMT}`, `${DNT}` | `1` when the viewer opted out or no ID exists |
| `${USER_AGENT}` | `${DEVICEUA}`, `${CLIENTUA}` | the user agent the library sends |
| `${IP}` | `${DEVICEIP}` | from `setIp`, otherwise empty |
| `${RANDOM}` | `${CACHEBUSTING}`, `${CACHEBUSTER}` | 8-digit cache buster, fresh per URL |
| `${TIMESTAMP}` | | epoch milliseconds |
| `${APPBUNDLE}`, `${APPNAME}`, `${APPVERSION}` | | host app identity |
| `${DEVICEMAKE}`, `${DEVICEMODEL}` | | manufacturer and model |
| `${OS}`, `${OSVERSION}` | | `android` and the release version |
| `${DEVICETYPE}` | | OpenRTB code: `3` TV, `4` phone, `5` tablet |
| `${CONNECTIONTYPE}` | | OpenRTB code: `0` unknown, `1` ethernet, `2` wifi, `3` cellular |
| `${LANGUAGE}` | | two-letter language of the device locale |
| `${PLAYERSIZE}`, `${DEVICEW}`, `${DEVICEH}` | | screen size in pixels |

Both spellings are accepted for every name — `${IFA}` and the VAST `[IFA]`, case-insensitive.
Values are URL-encoded. A known macro without a value collapses to nothing, so no leftover token
is ever sent. A name the library does not own is left exactly as it was, which is what keeps an
ad server's own `${PUID30}`-style parameters intact.

## 10. Ads outside a content pause

### Ad on an in-app action

`SwitchRollAd` is the same format opened by something the viewer did — picking a card, switching
a channel, opening a section — rather than by a pause. Configuration is identical to
`PauseRollAd`; what differs is the pair of calls and that there is usually no content to resume
afterwards.

```kotlin
switchRoll = SwitchRollAd(binding.root)
    .setTagUrl(VAST_TAG_URL)
    .setListener(adListener)
    .attach()

// wherever the action happens
switchRoll?.show("card:${card.id}")
```

| Call | Meaning |
|---|---|
| `show(action)` | the action happened; `action` names it in the logs and for a `Controller` |
| `dismiss()` | host takes the overlay down (leaving the screen, or moving on) |
| `attach()` / `detach()` | same as `PauseRollAd` |

`show` while an overlay from an earlier action is still up is ignored, so a viewer clicking
through a rail gets one ad rather than a queue. Once the ad is gone — skip, a creative that
played out, or `dismiss()` — the next action is a new opportunity.

The overlay does not take focus away permanently: nothing plays behind it, so `onOpen` usually
only has to stop the layout underneath from taking remote input, and `onClose` gives focus back.

### Ad on app open

`StartRollAd` is the show `SwitchRollAd` does, with one opportunity: a launch happens once.

```kotlin
startRoll = StartRollAd(binding.root)
    .setTagUrl(VAST_TAG_URL)
    .setBackdropVisible(true)
    .setListener(adListener)
    .attach()

// once the first screen is ready
startRoll?.show("cold")
```

| Call | Meaning |
|---|---|
| `show(reason)` | the app is open; `reason` names the launch in the logs — `cold`, `deeplink` |
| `dismiss()` | host takes the overlay down and goes on to its first screen |
| `attach()` / `detach()` | same as `PauseRollAd` |

Only the first `show` does anything: the launch ad has one turn per instance. An `onStart` after a
return from the background, or a screen the system rebuilt, therefore does not pay the viewer a
second ad. How that single opportunity ended — a show, no fill or an error — does not bring it
back. A new launch means a new instance.

A `show` before `attach()` does not spend the turn: it is a call-order mistake, it is logged, and
the next `show` after `attach()` works as the first one.

Call `show` once the container is laid out — the overlay covers whatever is on screen at that
moment. Behind it there is usually the app's own splash, which is why `setBackdropVisible(true)`
matters here more than in the other placements: without it the host's logo shows through the
transparent pixels of the creative.

There is nothing to resume afterwards: in the slot `onClose()` the host simply carries on with its
launch — opening the first screen, or handing focus to it.

## 11. Events

Slot window (`TriggerRoll.Listener`): `onOpen`, `onClose`, `onNoAd`, `onError(message)`.
`onOpen` pairs with exactly one `onClose`. No fill and errors before a show fire neither.

Tracking (`TriggerRoll.Listener`): the VAST 2–4.2 catalog, each callback receiving the URLs the
library pings itself. A tracking callback fires only when the response actually carries URLs for
that event — an empty group is not reported, so do not use tracking callbacks to detect the
state of the show. The table below says which events a format can produce at all.

| Event | Video | Banner |
|---|---|---|
| `impression`, `creativeView`, `loaded` | yes | yes |
| `start`, quartiles, `progress@offset` | yes | while a soundtrack plays |
| `mute` / `unmute`, `pause` / `resume` | yes | while a soundtrack plays |
| `viewable` after 2 s on screen, otherwise `notViewable` | yes | yes |
| `complete` + `closeLinear` | only if played out | if the soundtrack played out |
| `close`, `overlayViewDuration` | yes | yes |
| `skip` | yes | yes |

Failure mapping:

| Situation | Result |
|---|---|
| no `tagUrl`, or the tag cannot be fetched | `onError` |
| no usable creative | `onNoAd`, plus `onVastError` if the response carried Error URLs |
| creative playback fails | `onVastError` + `onError`, then slot `onClose` |
| soundtrack under a banner fails | `onVastError` + `onError`; the image stays |
| content resumed while the tag is loading | host `close()` — response dropped silently |
| content resumed while the ad is on screen | host `close()` — VAST `close`, then slot `onClose` |
| viewer skips | VAST `skip` / `onSkip`, then slot `onClose` |
| viewer taps landing | `ClickTracking`, device browser, VAST `close`, then slot `onClose` |

Each quartile and each `progress@offset` is sent once per show.

Wrapper chains are unwrapped up to five hops, merging Impression, Error, and TrackingEvents
from every level.

## 12. Lifecycle rules

| Call | When | Thread |
|---|---|---|
| `attach()` | after the container exists (`onCreate`) | main |
| `trigger()` | host: pause (or later a seek) | main |
| `close()` | host: play / opportunity gone (`onStop`) | main |
| `detach()` | screen teardown (`onDestroy`) | main |

`attach()` is idempotent. `trigger` / `close` may be called many times on the same instance.
`detach()` is terminal: it stops the library's threads, drops the overlay, the host callbacks,
and the ad player. A later `attach()` on the same object is ignored with a log line — create a
new instance for the next screen. Do not pass the content player into the SDK; the listener
reads your `player` field, so `onClose` always sees the ExoPlayer that is alive now.

All configuration setters are main-thread. All listener callbacks arrive on the main thread.

## 13. Android TV and remotes

When chrome appears, skip takes focus. After that the library does not move it: the remote
navigates info, mute, pause, landing and skip. Hide the content player's chrome in `onOpen()` so the
remote cannot operate it under the overlay. After the overlay closes the library does not hand
focus back — restore the controller and focus in `onClose()`, as in the example above.

`BACK` and other keys are never intercepted.

## 14. What the format does not do

- No in-app browser. `ClickThrough` from the landing chip goes to the device browser and closes
  the show; info shows it as a QR without closing. `ClickTracking` is pinged either way.
  `CustomClick` is parsed but not acted upon.
- No interactive creatives: VPAID, SIMID, HTML and iframe resources are skipped.
- No ad pods: one opportunity shows one creative.

## 15. Versions and licence

The artifact version is SemVer and is readable at runtime as
`com.ctvhouse.sdk.SdkVersion.NAME`. Release history: [CHANGELOG.md](../CHANGELOG.md).

The library is proprietary: non-commercial use is permitted, commercial use is reserved to the
copyright holder. Full text in [LICENSE](../LICENSE).
