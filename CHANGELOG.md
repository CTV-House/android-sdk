# Changelog

Русская версия: [CHANGELOG.ru.md](CHANGELOG.ru.md).
Format: [Keep a Changelog](https://keepachangelog.com/), versions follow SemVer.

## 1.2.0 — 2026-08-13

### Added

- `SwitchRollAd`: the same format opened by an action in the app — picking a card, switching a
  channel — instead of a pause. `show(action)` / `dismiss()` replace `trigger` / `close`; the
  configuration setters are the ones `PauseRollAd` has.
- Device reporting: the library builds one device snapshot per screen (advertising ID and its
  opt-out flag, user agent, make, model, OS, device type, connection type, screen size, language,
  host app identity) and fills macros from it.
- The advertising ID is read from the Play Services identifier service, with the `Settings.Secure`
  pair on Amazon devices as a fallback and no Play Services dependency in the artifact. An
  opted-out viewer, a device without an ID and a host that removed the `AD_ID` permission all
  report an empty `${IFA}` with `${LIMITADTRACKING}=1`.
- `setDismissOnCreativeEnd(enabled)`: whether a creative that played out takes the overlay with
  it. `false` keeps the last video frame, or the still, on screen until skip or the host's close —
  for placements that must be dismissed by the viewer.
- `setBannerDurationSeconds(seconds)`: how long a still counts as playing when the response has no
  duration of its own. `0`, the default, leaves such a still waiting for the viewer.
- `setBackdropVisible(visible)`: `true` paints a black fill under the creative, for a placement
  that has to cover the content.
- Macro catalog: `IFATYPE`, `LIMITADTRACKING`, `TIMESTAMP`, `APPBUNDLE`, `APPNAME`, `APPVERSION`,
  `DEVICEMAKE`, `DEVICEMODEL`, `OS`, `OSVERSION`, `DEVICETYPE`, `CONNECTIONTYPE`, `LANGUAGE`,
  `PLAYERSIZE`, `DEVICEW`, `DEVICEH`, plus the VAST aliases of the existing names. Both the
  `${IFA}` and the VAST `[IFA]` spelling are accepted.

### Changed

- The overlay is transparent where the creative is not. It used to be filled with black, which hid
  the content behind a still that had transparent pixels or did not match the screen's aspect
  ratio; `setBackdropVisible(true)` brings the fill back.
- A still now ends its own show: when its soundtrack finishes, or after the `Duration` the response
  carries, or after `setBannerDurationSeconds`. Previously only a video creative dismissed itself
  and every banner waited for skip; `setDismissOnCreativeEnd(false)` restores the old behaviour.
- Macros are expanded in every URL the library sends, not only in the tag: tracking pixels, the
  creative URL and the `ClickThrough` behind the QR are filled from the same snapshot, so a pixel
  no longer leaves with `[IFA]` in it.
- `setIfa` / `setIp` moved onto the format, which is what makes them visible to trackers as well
  as to the tag. Both launchers still expose them; `setIfa` now overrides the platform ID rather
  than being the only source of one.
- The AAR declares three install-time permissions — `INTERNET`, `ACCESS_NETWORK_STATE` and
  `com.google.android.gms.permission.AD_ID` — none of which prompts the viewer. Hosts no longer
  declare `INTERNET` for the library, and a host under the Families policy can remove `AD_ID`
  with `tools:node="remove"`. Still no components, content providers or process-start hooks; the
  build check that verifies the artifact now enforces exactly that list.

## 1.1.0 — 2026-08-13

Baseline entry: it describes what the library contains rather than what changed, and later
releases are recorded against it.

### Formats

- TriggerRoll over a Media3 content player: a VAST creative shown when the host reports an
  opportunity, on phones and on Android TV.
- Video creative — VAST `Linear` with a progressive `MediaFile`, audio-only included. Plays on
  its own `ExoPlayer` and dismisses itself when it ends.
- Banner creative — VAST `Companion` `StaticResource` image, shown until skip or content resume.
  When the same response also has a Linear `MediaFile`, the companion is the pause creative.
  A dedicated audio `MediaFile` (`audio/*`) plays under the still; video files are not used
  as a soundtrack.

### VAST

- VAST 2 / 3 / 4.x: `InLine` and `Wrapper` with the `VASTAdTagURI` chain unwrapped up to five
  hops, merging Impression, Error and TrackingEvents from every level.
- `skipoffset`, ERID, `ViewableImpression`, and the full VAST 4.2 TrackingEvents catalog.
- Progressive `MediaFile` selection (`mp4` → `webm` → other, then audio); HLS, DASH and VPAID
  are skipped when a progressive variant exists.

### API

- `PauseRollAd` is a tag-URL launcher for `TriggerRoll`; `TriggerRoll` itself accepts markup
  from a host `Controller`. `Overlay` is the slot engine (internal constructor) — hosts do not
  construct it. The content player is not an SDK input: the host calls `trigger` / `close`.
- `TriggerRoll.Listener` delivers the slot window (`onOpen` / `onClose`) and the VAST tracking
  catalog, always on the main thread. Java hosts override only the callbacks they need.
- Configuration: `setSoundEnabled`, independent positions for skip, ad marking and the playback
  group, visibility of info / pause / mute, skip offset, request timeout, ad-marking and skip
  templates, `${IFA}` `${USER_AGENT}` `${IP}` `${RANDOM}` in the tag URL, diagnostic logging.

### Behaviour in the host process

- Nothing the library does can crash the host: library threads and everything posted to the main
  looper pass through a single failure barrier. Warnings and failures always reach logcat under
  the `CtvSdk/*` tags.
- Bounded resource use: 2 MB per ad markup, 8 MB per creative, images decoded downsampled to
  4 MPx, tracker pool capped at 2–6 threads with idle timeout.
- The library never reads or writes host playback. The host opens and closes each opportunity.
- `detach()` is terminal and releases the ad player, the library threads, pending main-loop work,
  the overlay, and every host callback.
- No permissions, components or synthetic dependencies: `androidx.annotation`, ZXing for the
  ClickThrough QR, and the Kotlin stdlib are brought in, and Media3 is `compileOnly` so the host
  chooses the version.

### Reporting

- Quartiles and `progress@offset` follow the creative's playback position rather than the clock.
- `complete` and `closeLinear` are sent only for a linear creative that played out; otherwise
  `close`.
- A viewable impression requires two seconds on screen, otherwise `notViewable` is reported.
- The info control shows a QR of the VAST ClickThrough (companion URL on a banner, linear
  otherwise) and pings `ClickTracking`.
- Redirects, including `http ↔ https` hops, are followed by the library up to five times.

### Licence

Proprietary: non-commercial use is permitted, commercial use is reserved to the copyright
holder. See [LICENSE](LICENSE).
