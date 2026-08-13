# FAQ — what the library does to your app

Русская версия: [FAQ.ru.md](FAQ.ru.md).
How to integrate: [INTEGRATION.md](INTEGRATION.md).

An ad library runs inside someone else's process, on someone else's player, on devices that
are often slow and long-lived. This page lists the ways that normally goes wrong and what the
library does about each one.

---

### Can a defect inside the library crash my app?

**The risk.** An ad SDK runs work on its own threads and posts to the main looper. An uncaught
exception on either path terminates the host process, and the crash is attributed to the app.

**What the library does.** Every task that runs on a library thread, and everything posted to
the main looper, goes through one failure barrier that catches `Throwable`, restores the
interrupt flag, and logs. Library threads additionally carry an uncaught-exception handler. A
defect turns into a log line and a missed ad, never into a process death.

### Will I be able to diagnose a problem in production?

**The risk.** A library that swallows its own exceptions and only logs behind a debug flag
leaves nothing to look at when something goes wrong in the field.

**What the library does.** Warnings and failures always reach logcat under the `CtvSdk/*`
tags. `setDebugLogging(true)` adds the step-by-step trace on top; it is not needed to see an
error. The debug flag is process-wide, so keep it to test builds — `detach()` turns it back off
only for the instance that turned it on.

### What happens to my player?

**The risk.** Ad libraries like to take over playback: pausing, resuming, seeking, or attaching
themselves to the host player permanently.

**What the library does.** Nothing: it does not add a listener to your content player and does
not read or write `playWhenReady`. You call `trigger` when you want a show and
`close` when that window is gone. Resume content in slot `onClose` by writing your own `player`
field — the library never holds the content player. Hide the content player's chrome in
`onOpen` the same way. The ad itself plays on a separate `ExoPlayer` that the library owns
and releases.

### Can I stop the ad from making noise?

**The risk.** An ad that starts at full volume on a device where the viewer was listening to
something else is a complaint, and an ad player that grabs audio focus while playing nothing
audible ducks other apps for no reason.

**What the library does.** Sound is `setSoundEnabled(false)` (or the constructor argument
`soundEnabled = false`): every creative plays at zero volume and never requests audio focus.
With sound on, the ad requests focus like any other media playback. Call it before the
creative starts.

### Can it leak my Activity?

**The risk.** Overlay views hold the Activity context. If the library keeps them after the
screen is gone, the Activity stays in memory.

**What the library does.** `detach()` removes the overlay from your container and releases the
whole chrome object, the host listener, and the ad player. The only context the library holds
afterwards is the application context. Hosts that keep a field pointing at the detached instance
do not keep the screen alive.

### How much memory can one creative cost me?

**The risk.** A hostile or simply broken ad server can answer with a gigabyte of markup or a
50-megapixel image, and the host process is the one that gets the `OutOfMemoryError`.

**What the library does.** Response bodies are bounded: 2 MB for ad markup, 8 MB for a
creative. Images are decoded downsampled to at most 4 MPx (≈16 MB as ARGB_8888). Anything over
the limit is treated as unusable, not truncated.

### Which threads does it create?

**The risk.** Background pools that are sized for a server, or that stay parked for the life of
the process, cost battery and memory on a TV box.

**What the library does.** One single-threaded executor for markup and creative downloads, and
one bounded pool for tracker pings (`availableProcessors()`, clamped to 2–6) whose idle threads
time out after 30 seconds. All threads are daemon threads, and both pools are shut down by
`detach()`.

### Will it conflict with my dependencies?

**The risk.** A library that bundles an XML parser, an HTTP client, or its own Media3 copy
causes duplicate classes or silently upgrades the host's versions.

**What the library does.** It brings `androidx.annotation`, ZXing (`com.google.zxing:core`) for
the ClickThrough QR, and the Kotlin stdlib. Media3 is `compileOnly`, so your version wins. The
XML pull parser comes from the Android platform; the JVM implementation used by the tests never
enters the artifact.

### What does it add to my manifest and APK?

Three install-time permissions and nothing else: `INTERNET`, `ACCESS_NETWORK_STATE` for the
connection type ad servers ask for, and `com.google.android.gms.permission.AD_ID` for the
advertising ID on Android 13+. None of them prompts the viewer. There are no components, no
content providers and no process-start hooks — nothing in the library runs before the host
creates a format. Consumer R8 rules ship inside the AAR, and a build check refuses to publish an
artifact whose manifest grew anything beyond those three lines.

A host under the Play Families policy can drop the ad ID from the merged manifest:

```xml
<uses-permission android:name="com.google.android.gms.permission.AD_ID"
    tools:node="remove" />
```

The library then reports no advertising ID and `${LIMITADTRACKING}=1`, and everything else keeps
working.

### Does the library read the advertising ID itself?

Yes, and it does so without a Play Services dependency: it talks to the identifier service
directly, and falls back to the `Settings.Secure` pair Amazon devices publish. A device with
neither — a bare Android TV box, for instance — simply has no ID, which is reported honestly
rather than replaced by a fingerprint of the device.

The lookup binds a system service, so it runs on a background thread when `attach()` is called
and no host call waits for it. An opted-out viewer is never given a zeroed ID to pass on:
`${IFA}` stays empty and the opt-out flag is set. A host that manages consent itself passes its
own ID through `setIfa` and that wins.

### Does it work from Java?

Yes. The listener interfaces compile to real default methods, so a Java host overrides only the
callbacks it needs instead of the whole tracking catalog. The setters return the concrete format
type, so the configuration chain needs no casts — a Java test in the build keeps it that way.

### What happens when the ad server misbehaves?

| Situation | Result |
|---|---|
| slow response | cut off by `setRequestTimeoutMs` (10 s by default), 5 s for tracker pings |
| broken or empty markup | `onNoAd()`; the overlay never appears |
| companion image fails to download | Linear `MediaFile` if the tag has one, otherwise `onNoAd()`, no empty overlay, no impression |
| creative fails to play | `onVastError` + `onError`, overlay hidden, content stays paused |
| response arrives after the viewer resumed content | dropped; nothing is shown or reported |
| Wrapper chain that never ends | stopped after five hops |
| redirect loop | stopped after five redirects |

### Why do redirects need special handling?

**The risk.** `HttpURLConnection` refuses to follow a redirect that changes scheme, and ad tags
and trackers hop between `http` and `https` constantly. Silently dropping those responses costs
impressions.

**What the library does.** It follows redirects itself — up to five hops, resolving relative
`Location` headers and refusing anything that is not `http` or `https`.

### Why does an `http` tag or tracker fail in my app?

**The risk.** Since Android 9 an app blocks cleartext traffic unless it opts in, and ad servers
still hand out plain `http` tags, creatives and trackers. The library cannot lift that policy —
it belongs to the host manifest — so those requests fail and the pixels are lost.

**What to do.** Prefer `https` endpoints from the ad server. Where an `http` host is unavoidable,
the host app permits exactly that domain through its own network security config; the library
follows `http ↔ https` redirects on its own, so a tag that upgrades itself needs nothing.

### Can the overlay end up under my content?

The ad surface is marked as a media overlay so it sits above the content surface, and the
overlay root is brought to the front before every show. Views added to the container after
`attach()` therefore cannot cover the creative.

### Does the overlay steal input?

While visible it consumes touches, which is what keeps the viewer from operating the player
behind the ad. It never intercepts `BACK` or other keys. On remote-controlled devices focus
moves to skip and stays there unless the viewer moves it; the library does not restore focus after
the overlay closes, so hide the content player's chrome in `onOpen()` and restore controller,
focus, and playback in `onClose()`.

### Can I use several instances?

One instance serves one screen and becomes terminal after `detach()`. Instances share no
state except the debug-logging flag, and each owns its own threads, overlay, and ad player.

### Is anything reported that did not actually happen?

`complete` and `closeLinear` are sent only for a linear creative that played out; resuming
content or closing the overlay produces `close` instead. Quartiles and `progress@offset` follow
the creative's playback position rather than the clock, so buffering does not advance them. A
viewable impression requires two seconds on screen — a shorter show reports `notViewable`.
Every quartile and offset is sent once per show.
