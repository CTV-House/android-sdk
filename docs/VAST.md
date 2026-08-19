# VAST requirements

Русская версия: [VAST.ru.md](VAST.ru.md).
How this looks from the app side: [INTEGRATION.md](INTEGRATION.md).

For whoever sets up the campaign and the ad server: what the response to a tag URL must carry for
`PauseRollAd`, `SwitchRollAd` and `StartRollAd` to show a creative. All three launchers are the
same `TriggerRoll` format over the same VAST parser, so the requirements are shared; how the
placements differ, and what each is better filled with, is §2.

## 1. Response

| | |
|---|---|
| Request | `GET` on the tag URL, `Accept: */*`, own User-Agent |
| Response | `2xx`, XML in UTF-8; up to 5 redirects, scheme changes included |
| Version | VAST 2.x, 3.x, 4.x (`version` is not checked) |
| Size | up to 2 MB per document, Wrapper hops included |
| Timing | 10 s for the tag and for every hop (the host may change it) |
| Empty response | `2xx` with no `<Ad>` is no fill — the app gets `onNoAd()` |

Namespace prefixes are ignored, CDATA around URLs is fine, and whitespace inside numeric
attributes is tolerated.

The **first** `<Ad>` is used. If it is a `<Wrapper>`, the `VASTAdTagURI` chain is followed up to
five hops (`followAdditionalWrappers="false"` is honoured); `Impression`, `Error`,
`TrackingEvents` and `ClickTracking` from every level are merged. Ad pods (`sequence`) are not
supported: one opportunity, one show.

Every URL in the response goes through macro substitution — creative, trackers, `ClickThrough`.
The names and their aliases are in [INTEGRATION.md §9](INTEGRATION.md); both spellings are
accepted, `${IFA}` and `[IFA]`. A name we do not own is left as it was, `[ERRORCODE]` included —
the library does not fill it.

## 2. Three placements

The opportunity is opened by the app, not by the library: it never listens to the content player
and never picks the moment itself. Every opportunity is a fresh request for the tag URL — no cache,
no prefetch, and no second request while the first is in flight or an overlay is on screen. A
response that arrives after the app closed the opportunity is dropped silently: there was no show,
so nothing is counted.

`PauseRollAd` — a pause in playback:

1. the viewer pauses and the app calls `trigger("pause")`;
2. the library fetches the tag, unwraps the Wrapper chain, downloads the creative;
3. the creative appears over the player — that is where `Impression` and the rest of the tracking
   come from; no fill means no overlay at all and no impression;
4. the show ends with the viewer (skip), with the creative itself, or with the pause being lifted,
   in which case the app calls `close()`;
5. the app resumes content. The library does not.

`SwitchRollAd` — an action the viewer took: picking a card, switching a channel, opening a section.
Same cycle, with `show("channel_switch")` instead of `trigger`, and usually nothing playing behind
the overlay: the viewer is waiting for the transition, and the app opens what was picked after the
show. A `show` while an earlier overlay is still up is ignored — a viewer clicking through a rail
gets one ad, not a queue of them.

`StartRollAd` — the app opening. The switch cycle again, with one opportunity per launch: the app
asks once, when its first screen is ready, and does not ask a second time in that launch. Behind
the overlay sits the app's own splash, and there is nothing to resume after the show.

What to answer with:

| Placement | Both formats | Usually filled with |
|---|---|---|
| PauseRoll | yes | a fullscreen companion still (§5): playback is paused behind the overlay, and a still waits for the viewer instead of arguing with the reason they paused |
| SwitchRoll | yes | `Linear` video with sound and a `skipoffset` (§4): the viewer is waiting for the transition, and that wait is the slot — 10–15 seconds read as a bumper before content |
| StartRoll | yes | `Linear` video, 10–15 seconds (§4): the viewer has just come into the app and is waiting for the first screen |

No launcher is tied to a format: `PauseRollAd` plays video on a pause exactly as `SwitchRollAd` and
`StartRollAd` show a still. The response decides (§3), under one rule for all three — **when the
response carries a companion and a Linear, the companion wins**. So to play video on a pause, do
not send a companion; to leave a still up after a channel switch or on a launch, sending the still
is enough.

Video on a pause is worth keeping short: while it plays the viewer cannot get back to the content,
and when it ends the creative dismisses itself and the app resumes playback — a long spot on a
pause lifts the pause for the viewer.

A still on a switch or a launch works too, but nothing inside it can end the show: the viewer is
waiting for the transition and a companion has no duration of its own. Send `Duration` in the
`Linear` of the same response (or a soundtrack, §5) and the still goes by itself, after which the
app opens what was picked or its first screen. Without one the show holds until a skip, which
becomes available after 5 seconds.

The placements are still worth separate tag URLs: the pause one then does not get preroll video
from a shared campaign, and reporting does not mix a skipped video with a still that was watched.

For a still on a pause `Duration` is optional: the app usually keeps it up until the viewer acts.
Send one when the media plan says the show should end by itself.

## 3. Which format appears

| In the response | On screen |
|---|---|
| `Companion` with a `StaticResource` | banner |
| `Linear` with a playable `MediaFile` only | video |
| both | banner; video is used if the still fails to download |
| neither | nothing, `onNoAd()` |

The response decides, not the app. Pause tags often leave the ad server with both creatives — a
Linear from the preroll template and a fullscreen companion; the still is the pause creative. If
the banner is meant to be a banner, the Linear can be left out.

## 4. Video — `Linear`

The first `<Creative>` with a `<Linear>` inside the first `<InLine>` is used.

Required:

- A `<MediaFile>` with an absolute `http(s)` URL. Protocol-relative `//cdn/...` will not do.
- Progressive delivery: `delivery="progressive"` or no attribute at all.
- `<Duration>` as `HH:MM:SS` or `HH:MM:SS.mmm`. Without it the creative still plays, but quartiles
  and percentage `progress` are not sent — there is nothing to count against.

Recommended:

| | |
|---|---|
| Container | MP4, H.264 + AAC |
| Resolution | 1920×1080, scaled to the screen |
| Bitrate | up to 8 Mbps; CTV devices read the file off the network, not out of a cache |
| Duration | 15–30 s |
| Attributes | `type="video/mp4"`, `width`, `height`, `bitrate` |

Set `type`: without it the container is inferred from the URL path (`.mp4`, `.webm`, `.mov`,
`.m4v`, `.3gp`, `.mkv`, `.ogv`), and the query string does not count.

With several `MediaFile`s one is picked: progressive video first, then progressive audio; among
equals the container (`mp4` → `webm` → `3gp` → other), then `width × height`, then `bitrate`.
Which is why sizes and bitrate are worth filling in — otherwise the pick is blind.

Neither played nor considered: VPAID (`apiFramework="VPAID"`), HLS and DASH manifests (`.m3u8`,
`.mpd`, MIME containing `mpegurl` or `dash+xml`). A response carrying only those is no fill.

An audio-only `MediaFile` (`audio/*`, or `.mp3`, `.m4a`, `.aac`, `.ogg`, `.wav`, `.flac`) counts as
a video creative without a picture: the sound plays and the screen stays behind the controls.

`skipoffset` on `<Linear>` is read only as an absolute value (`skipoffset="00:00:05"`). A
percentage is ignored, and the host setting — or the 5 s default — takes over.

## 5. Banner — `Companion`

The first `<Companion>` with an `http(s)` `<StaticResource>`, in any `<Creative>` of the response,
is used. `width` and `height` do not affect the pick, so several sizes buy nothing — send one
fullscreen still.

Required:

- `<CompanionAds><Companion><StaticResource>` with an absolute `http(s)` URL.
- A raster file Android decodes: PNG, JPEG, WebP. `creativeType` is not checked, but fill it in.

Recommended:

| | |
|---|---|
| Format | PNG (alpha included) or JPEG |
| Resolution | 1920×1080 |
| File size | up to 1 MB; the hard limit is 8 MB, and decoding downsamples to 4 MPx |

The still is shown fullscreen, `FIT_CENTER`. Transparent pixels, and the letterbox around a
creative of another aspect ratio, stay transparent — the app's content shows through unless the
publisher turned the black backdrop on.

A `<NonLinear>` with a `<StaticResource>` is **not** shown as a banner: only tracking URLs are
taken from `NonLinearAds`. The fullscreen still belongs in `CompanionAds`.

Nothing inside a still can end its show, so it is timed by, in this order:

1. an audio `MediaFile` in the `Linear` of the same response — a soundtrack under the picture,
   whose end is the end of the creative;
2. `<Duration>` from the `Linear` of the same response;
3. an app setting (`setBannerDurationSeconds`), which by default means no timer and the viewer
   takes the still down.

The first two are the ad server's to control: for a banner that holds 15 seconds, put
`<Duration>00:00:15</Duration>` in a `Linear` next to the companion.

## 6. Click and ad marking

`ClickThrough` (`CompanionClickThrough` for a banner) must be `http(s)`, or the info control does
not appear. No browser is opened: the landing URL is shown as a QR code and `ClickTracking` is
pinged on press. `CustomClick` is parsed but leads nowhere.

The ERID for the marking chip is taken, in order of preference, from:

```xml
<Erid>3axxx</Erid>
<Extension type="ERID">3axxx</Extension>
<Extension type="nroa_inform">
  <Title>Advertiser LLC</Title>
  <Url>https://…</Url>
  <Erid>3axxx</Erid>
</Extension>
```

Any of the three, in `InLine` or in `Wrapper`. Without one the chip reads "РЕКЛАМА" with no
identifier — not enough for a show in Russia.

## 7. Tracking

`<Impression>` is required: it is the counted show, sent the moment the creative appears.

`<Tracking>` URLs are pooled from `Linear`, every `Companion` and every `NonLinear`, and matched by
event name case-insensitively. Where a URL sits does not matter; whether the event makes sense in
that node does.

| Event | Video | Banner |
|---|---|---|
| `impression`, `creativeView`, `loaded` | yes | yes |
| `start`, `firstQuartile`, `midpoint`, `thirdQuartile` | yes | while a soundtrack plays |
| `progress@offset` | yes | while a soundtrack plays |
| `mute` / `unmute`, `pause` / `resume` | yes | while a soundtrack plays |
| `complete`, `closeLinear` | if it played out | if the soundtrack played out |
| `skip` | yes | yes |
| `close`, `overlayViewDuration` | yes | yes |
| `ViewableImpression` → `Viewable` / `NotViewable` | yes | yes |

Worth knowing while trafficking:

- Quartiles are counted off the playback position, not the clock, and need `<Duration>`.
  Buffering does not move them forward.
- `progress` takes `offset="00:00:10"` and `offset="50%"`; a percentage without `<Duration>` never
  fires.
- `Viewable` is sent after 2 seconds on screen. A shorter show sends `NotViewable`.
  `ViewUndetermined` is parsed but never sent.
- `close` and `overlayViewDuration` mean a show that ended without a skip. A skip sends `skip`.
- Every event, and every `progress@offset`, is sent once per show.
- Pixels are plain `GET`s, the body is never read, the timeout is 5 seconds, and up to 5 redirects
  are followed.
- `<Error>` is pinged when there is no playable creative and when playback fails.
  `[ERRORCODE]` is not filled in.

## 8. Not supported

Parsed, but not executed:

- VPAID, SIMID and any interactive creative; `InteractiveCreativeFile`;
- `HTMLResource` and `IFrameResource` in a `Companion`;
- `<NonLinear>` as a creative (tracking only);
- HLS and DASH in a `MediaFile`;
- ad pods (`sequence`) and several `Creative`s in one show;
- `Icons`, `AdVerifications` (OMID), `Mezzanine`, `Survey`.

## 9. Examples

A minimal video:

```xml
<VAST version="4.0">
  <Ad>
    <InLine>
      <AdSystem>Ad Server</AdSystem>
      <AdTitle>Campaign</AdTitle>
      <Erid>3axxx</Erid>
      <Impression><![CDATA[https://track/imp?cb=${RANDOM}]]></Impression>
      <Error><![CDATA[https://track/err]]></Error>
      <Creatives>
        <Creative>
          <Linear skipoffset="00:00:05">
            <Duration>00:00:20</Duration>
            <TrackingEvents>
              <Tracking event="start"><![CDATA[https://track/start]]></Tracking>
              <Tracking event="complete"><![CDATA[https://track/complete]]></Tracking>
              <Tracking event="progress" offset="00:00:10"><![CDATA[https://track/p10]]></Tracking>
            </TrackingEvents>
            <MediaFiles>
              <MediaFile delivery="progressive" type="video/mp4" width="1920" height="1080"
                         bitrate="6000"><![CDATA[https://cdn/creative.mp4]]></MediaFile>
            </MediaFiles>
            <VideoClicks>
              <ClickThrough><![CDATA[https://advertiser/landing]]></ClickThrough>
              <ClickTracking><![CDATA[https://track/click]]></ClickTracking>
            </VideoClicks>
          </Linear>
        </Creative>
      </Creatives>
    </InLine>
  </Ad>
</VAST>
```

A minimal banner that holds itself for 15 seconds:

```xml
<VAST version="4.0">
  <Ad>
    <InLine>
      <AdSystem>Ad Server</AdSystem>
      <AdTitle>Campaign</AdTitle>
      <Erid>3axxx</Erid>
      <Impression><![CDATA[https://track/imp?cb=${RANDOM}]]></Impression>
      <Creatives>
        <Creative>
          <Linear>
            <Duration>00:00:15</Duration>
          </Linear>
        </Creative>
        <Creative>
          <CompanionAds>
            <Companion width="1920" height="1080">
              <StaticResource creativeType="image/png"><![CDATA[https://cdn/still.png]]></StaticResource>
              <TrackingEvents>
                <Tracking event="creativeView"><![CDATA[https://track/view]]></Tracking>
              </TrackingEvents>
              <CompanionClickThrough><![CDATA[https://advertiser/landing]]></CompanionClickThrough>
              <CompanionClickTracking><![CDATA[https://track/click]]></CompanionClickTracking>
            </Companion>
          </CompanionAds>
        </Creative>
      </Creatives>
    </InLine>
  </Ad>
</VAST>
```
