# OpenDTV: ISDB-T analyzer and live viewer for the IT9175-based dongle (Pad TV / GMA Now, 048d:275a)

Basic Java Android app. Layout:

- **Top left:** video player
- **Top right:** Virtual Number, Channel name, Program ID (+ signal line, channel picker, source buttons)
- **Bottom:** Video ID, Audio ID, Codec, then tabs **Analyzer / EPG / Datacast**

## Build

Open this folder in Android Studio and let it sync (it creates the Gradle wrapper). If it suggests newer
AGP / Media3 versions, accept them. Run on a phone with USB host (OTG) support.

## Update (hardware confirmed)

The tuner locks and plays on a real PT275. Changes after the first hardware test:
- USB TS reads now use 24 queued `UsbRequest` transfers (16 KiB each) plus a separate consumer thread; the old
  one-at-a-time blocking `bulkTransfer` lost packets (audio timestamp discontinuities in logcat, choppy video).
  The signal line shows `USB drop N` if the consumer ever falls behind (should stay absent).
- Signal Strength / Quality bars, dBm, polled once a second off the UI thread.
- **Scan** (quick list from the vendor app, or full 473.143 to 695.143 MHz (every 6 MHz)) and **Manual** tune (MHz, kHz or UHF
  channel number); found multiplexes are saved and listed in the new spinner.
- ExoPlayer: live-friendly buffer sizes and `FLAG_ALLOW_NON_IDR_KEYFRAMES` for faster start.
- FFmpeg software decoders (NextLib `nextlib-media3ext`, GPL-3.0) are registered as extension renderers after MediaCodec, so formats the phone cannot decode fall back to FFmpeg. Media3 is now 1.7.1 (compileSdk 35).

## What works now

| Part | State |
|---|---|
| TS demux, PAT/PMT/SDT/NIT/EIT/TDT-TOT parser, PID stats, CC/TEI/sync-loss counters | Done and tested (`tools/TsSelfTest.java`) |
| Single-service filter (rewrites PAT so ExoPlayer sees one program) | Done and tested |
| Analyzer / EPG / Datacast tabs, Virtual Number, IDs, Codec | Written (Android layer not compiled here, see below) |
| ExoPlayer playback through `TsPipeDataSource` | Written, untested on a device |
| **Open .ts file** source (develop and test without the dongle) | Done |
| USB: open device, claim interface, read bulk-IN | Written |
| **Tuner init, tuning, lock, signal registers** | **Written.** The 9175 path is the MIT clean-room core `ph.pbcnx.it9175` (the in-tree `It9175Tuner` was deleted on 2026-11-04 and its PC diff test with it); `CleanRoom9175TunerSource` drives it. Composes to 497.143 kHz (TV5) after init. Hardware verification of this path is still pending. |

**First hardware test:** open the app (or UsbProbeActivity) with the dongle and paste back the whole probe report.
It lists the registers that select the vendor's code paths (`regs before/after tuner init`, `regs after tune`),
the tuner-firmware values (`ec86/ed03/ed23`), `lock: true/false`, the raw signal registers and the TS byte count.
Not ported: the ALPU authentication the vendor does at the end of init (only gates the vendor's own ReadTS in
software), crystal indexes other than 0/1, EEPROM-configured variants.

Without hardware, use a **.ts** capture to exercise everything else.

### Test the tuner port (historical)

The PC differential test that compared the old in-tree `It9175Tuner` against the vendor library's
traces was deleted on 2026-11-04 together with that driver: the 9175 path is now the MIT clean-room
core in `ph/pbcnx/it9175`. `tools/golden/tuner_golden.txt` and `tools/emu/` remain as the record of
how those traces were produced; there is no build target that uses them.

## Tests (no device needed)

```
tools\tests.ps1                   (or: gradle :app:testDebugUnitTest from opendtv/)
```

The suite is 103 JUnit tests in 14 classes: the TS parsers (PAT/PMT/SDT/NIT, EIT, section
reassembly, packet history), the ARIB caption PES decoder, the EWBS area table, firmware identity,
the IT9175 command framer, the pipe between tuner and player, the single-service filter (its PAT
and record tee), and the channel-file round trip. The channel-file tests run against the real
org.json artifact because Android's is a stub on the JVM (see app/build.gradle).

A release block must quote the suite's line; docs/QUALITY.md records where it sits relative to the
workspace gate.

The old PC-only `javac` route over `ph/opendtv/ts/*.java` no longer compiles: three classes in that
package (`AacSniffingReader`, `AacTsPayloadReaderFactory`, `TsExtractorFactory`) implement Media3
extractor interfaces, so the package needs the Android/Media3 classpath. `tools/TsSelfTest.java` is
kept only as a record of the pure-parser self test.

## Notes and known limits

- **Virtual Number** = `remote_control_key_id` from the NIT (TS information descriptor). When a multiplex has
  several TV services, `.N` is appended in service_id order. This is a heuristic; adjust to the NTC channel plan.
- **Times** (EPG, clock) are shown as broadcast. ARIB specifies JST (UTC+9); Philippine broadcasters may differ.
- **Text** decoding is simplified: printable ASCII only. Kanji/kana (ARIB 2-byte sets) are skipped.
- **Datacast**: DSM-CC data carousels (stream types 0x0B-0x0D) are decoded (DSI/DII/DDB): modules, block
  progress, and for ARIB BML modules the MIME multipart files inside (name, type, size). Verified on a real
  TV5 (497.143 MHz) capture: 14 modules / 40 files on PID 0x02F8 (twitter_*.btb, 23 JPEGs, cms_text_*.btb) and
  startup.bml + common.clt on PID 0x02EE. Files are listed, not rendered. zlib-compressed modules
  (descriptor 0x09 / 0xC2) are supported and unit-tested, but no real compressed module has been seen yet.
  The OTA "ssu" service (type 0x0C) is listed with its module sizes. Caption streams (0x06) are listed separately.
- **Tools**: `tools/TsDump.java` prints services, streams, EPG, carousels and the PID table for any .ts file.
  `tools/TsExtract.java` saves every file of every complete carousel module
  (`java -cp out TsExtract input.ts outDir`), with an `index.txt`. Incomplete modules are listed but not saved;
  a longer capture (one or more full carousel cycles, e.g. 60 s+) completes more of them.
- **Codecs** come from PMT stream types. MPEG-2 video playback depends on the phone having an MPEG-2 decoder.
- No descrambling and no B-CAS code is included or planned.
- Layout works in portrait and landscape; landscape matches the top-left / top-right design best.


## Where screens and their files live

A screen is placed with the package that owns its logic, not with the activity that happens to
start it. Full-application screens that own the watch surface live in com.pbcnx.opendtvmod (MainActivity);
a diagnostic or bench surface lives with its package (com.pbcnx.opendtvmod.analyzer.AnalyzerActivity,
com.pbcnx.opendtvmod.dev.DeveloperOptionsActivity next to the dev classes it drives). Activities are
registered in the manifest by their real package, and the debug manifest carries the bench-only
screens so a release build cannot launch them.

## Layout of the code

```
com.pbcnx.opendtvmod           MainActivity, WatchPlayback, LicensesActivity
com.pbcnx.opendtvmod.dev       DeveloperOptionsActivity and the hidden-screen state
com.pbcnx.opendtvmod.analyzer  AnalyzerActivity, UsbProbeActivity (endpoint diagnostic), panels and state
com.pbcnx.opendtvmod.tuner     TunerHub, ChannelStore/ChannelFile/ChannelEntry, MuxSweep/MuxWait/ServiceWait,
                     DisplayModes/MhzLabel
com.pbcnx.opendtvmod.ts        TsDemux, ServiceInfo/StreamInfo, PSI/SI, EIT, EWBS, TsUtil; ts.pipe
                     (ServiceFilter, TsPipe), ts.caption, ts.carousel (Carousel*, MimeParts), ts.extractor
com.pbcnx.opendtvmod.source    TsSource, FileTsSource, the tuner sources (clean-room 9175/9308, SmsRio, dib0700)
ph.pbcnx             vendored driver cores behind those sources (it9175, it9308, smsrio, dib0700)
com.pbcnx.opendtvmod.player    PlayerFactory, TsPipeDataSource, DecoderPrefs (Media3)
com.pbcnx.opendtvmod.ui        Shared widgets; ui.watch: MainScreen, dialogs, SettingsController, scan and
                     playback controllers, cards, caption views, recorder
```

## Live Channels (Android TV / TV box, Android 9+ / API 28+)

The same APK also registers a **TV input** ("OpenDTV ISDB-T") for the system *Live Channels* app (TV Input Framework).
`minSdk` is now 28.

Setup on the TV box:
1. Install the APK, plug in the dongle (048d:275a) and allow the USB permission dialog.
2. Open **Live Channels -> Settings -> Channel sources** (or the "OpenDTV channel scan" icon on the launcher),
   choose **OpenDTV ISDB-T** and run **Quick scan** or **Full scan**. Found services are written to the TV provider.
3. Watch from Live Channels; zapping inside one multiplex does not retune the dongle.

Code (`com.pbcnx.opendtvmod.tv` is the TV-input surface; the hub it uses lives in `com.pbcnx.opendtvmod.tuner`):
```
OpenDtvInputService  TvInputService entry point
TunerSession         one viewing session: tune -> wait PAT/PMT -> ServiceFilter -> TsPipe -> ExoPlayer -> TV surface,
                     audio-track menu, EPG sync, retry on player error
TunerHub (tuner pkg)  the one TunerSource (through TunerRegistry) shared by sessions and scan; USB permission, ownership, idle power-down (30 s)
SetupActivity        D-pad friendly scan screen, writes channels (TvContract) and the EPG events seen during the scan
TvChannels           TV provider helpers (channels, programs, broadcast-clock -> UTC offset)
```
Notes: one tuner = one session at a time (`tunerCount=1`); recording and time-shift are not supported. EPG times use the
offset between the broadcast TDT/TOT and the device clock (rounded to 15 min, JST fallback), so the device clock must be right.
Channel numbers are the NIT `remote_control_key_id` (e.g. `5.1`); services without one get `100 + UHF channel` (e.g. `118.1`).
Like the rest of the tuner code, this is not yet tested on real hardware.

## Data broadcast (BML) viewer
Analyzer -> "Remote" tab -> DATA opens the full ARIB data-broadcast display over the video (video shrinks/moves as the page asks).
Keys: arrows, OK, BACK, Blue/Red/Green/Yellow, 0-11 (on screen, or hardware D-pad / number / colour keys).
Engine: web-bml by otya128 (MIT) bundled as `app/src/main/assets/bml/bml.js` (source: github.com/otya128/web-bml,
built by `tools/bml/build.mjs` from the engine pinned in `tools/bml/engine.lock.json`). Java only filters the data-carousel PIDs and feeds them in (`bml/BmlController`).

### Data broadcast in the main screen (landscape)
Landscape: no on-screen DATA button (use the DATA / F5 key of a remote or keyboard). Portrait: the floating remote button (bottom right) opens a panel with navigation, OK, the 4 colour keys, DATA and BACK.
Controls (only active in landscape):

| Action | Remote | Keyboard |
|---|---|---|
| Show / hide data broadcast | DATA key (`KEYCODE_TV_DATA_SERVICE`); remotes without it: TEXT key (`KEYCODE_TV_TELETEXT`) | F5 |
| Move | D-pad | Arrow keys or W A S D |
| Select | OK / Enter | Enter / numpad Enter |
| Back (page back) | BACK | F6 or Backspace |
| Colour keys | Red / Green / Yellow / Blue | F1 / F2 / F3 / F4 |
| Numbers | 0-9 | 0-9 and numpad 0-9 |
| Close | DATA again | F5 or Esc |

While the data broadcast is on screen these keys go to the page; otherwise the arrows keep changing channel / opening the side panel.

**Cache:** hiding the data broadcast does not destroy the page. The WebView is kept (timers paused, no packets fed, no CPU),
so DATA again is instant. A hidden page is destroyed after 3 minutes, on low memory, when the channel changes, a scan starts,
or the app goes to the background.

**Rotation:** MainActivity handles orientation changes itself (`onConfigurationChanged` re-inflates `layout` / `layout-land` /
`layout-sw600dp-land`) so the tuner and the player keep running. A plugged-in keyboard / remote no longer recreates the activity.

## Update: older WebView + CC / Audio language

- **BML on old WebView:** `bml.js` is now transpiled to Chrome 55-level syntax and `compat.js` (loaded first by `index.html`)
  polyfills `.at()`, `flatMap`, `replaceAll`, `matchAll`, `replaceChildren`, `Object.fromEntries`, ... To rebuild after updating
  web-bml: pin a new engine in `tools/bml/engine.lock.json`, then run `node tools/bml/build.mjs`. If the device has no / a disabled WebView, `BmlController` no longer
  crashes: the status line says the data broadcast is unavailable, and an un-runnable page reports the WebView version.
  A crashed WebView renderer is handled too (page dropped, app keeps playing).
- **Settings tab:** new *Closed Captions (CC)* toggle, *Caption Language* (1 main / 2 second) and *Audio Language*
  (lists the audio tracks of the playing channel; the choice is remembered and re-applied on other channels).
  PMT language descriptors (0x0A, 0xC4) are parsed into `StreamInfo.language`.

## Update: portrait BML remote, faster start, best audio

- Portrait shows the data broadcast over the 16:9 video; a Material 3 floating button (bottom right) opens the remote panel.
- Main activity only: playback starts after 25 ms of buffered media (was 1.5 s), 1 s after an underrun; PMT polling 20 ms.
- Audio: with no language chosen in Settings, the channel's best audio track is picked (channels > sample rate > bitrate > codec).

## Captions, BML sounds and fonts (from OpenDTVph-MOD)

- **Settings > Caption Display**: *Simple text line* (default, `CaptionOverlay`) or *Styled* (`ui/watch/RawCaptionView`): the caption PES
  is rendered by aribb24.js (`assets/bml/captions.html` + `captions.js`) in a transparent WebView over the video, with the
  broadcast layout and Latin / UCS coded captions. Styled mode needs a reasonably recent Android System WebView.
- **BML beeps**: ROM sounds 0..13 (`assets/bml/sounds/*.wav`) and AIFF sounds play natively (`BmlSoundPlayer`, called from
  `bml.js` through the `AndroidBml` bridge); if a sound is unavailable the page falls back to WebAudio as before.
  - **BML fonts**: BML-PH Sans (`assets/bml/fonts`) is the whole font stack - the round / bold round / square
    gothic slots, the fallback and the last resort all point at it, and captions draw with it too (2026-11-04: Kosugi
    was dropped with the 3.0.0 font removal and the caption surface moved onto this face). The JP build takes the
    rounded face (BMLPH-RoundGothic) for both the page and its captions.
- The BML engine itself (`BmlController`, web-bml in `bml.js`) is unchanged apart from these hooks.


## Nullability

Public API in this tree is `@NonNull` unless annotated otherwise. The AndroidX annotations are on the
seams other packages and the dialogs call: `TsSource`/`TunerProvider`/`TunerRegistry` (the nullable
`getTunerSignal()` and `find()`), `TunerHub` (`signal()`, `source()`, `lastError()`, `findDevice()` and `sourceName()`
are nullable; `demux()`, `lastMessage()` and `get()` are not), `ChannelStore`
(`pickStart()` is nullable; the list/set/order getters are not), `TsDemux` (the service/event/carousel lists and `sample()` are non-null; `getNetworkName()` is
unannotated prose),
`ServiceFilter` (`captionProbe()` is non-null; `recordError()` is nullable), `CaptionProbe` (`last()` nullable),
`MainScreen` (`audioPlayer()`, `audioTrackLabel()`, the summaries are non-null; `autoAudioGroup()` may
be null), `PlayerFactory` (players/renderers non-null) and `DeveloperSurface` (`demux()` and
`handler()` nullable). The policy is what matters: a reference return is non-null unless it says
`@Nullable`, and every nullable one has that annotation at its declaration.
