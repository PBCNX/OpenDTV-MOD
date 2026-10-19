# OpenDTV

**An open source Android ISDB-T player application built for ISDB-T compliant TV dongle tuners.**

OpenDTV lets you watch free-to-air digital TV on your Android phone, tablet, or TV box by plugging in a USB ISDB-T dongle. It talks to the tuner directly over USB host (OTG), so no vendor app is needed. It also includes a transport stream analyzer, EPG, closed captions, and a full ARIB data broadcast (BML) viewer.

![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Compatible Hardware

| Device | USB ID | Status |
|---|---|---|
| Mygica PadTV PT275 | `048d:275a` | Supported |
| GMA Now Dongle | `048d:275a` | Supported |
| IT9175 Tuner (ITE/Afatech) | `048d:275a` | Supported |

> All of the above are built around the ITE IT9175 chip. More ISDB-T dongles may be added over time. Contributions are welcome.

Your Android device must support **USB Host (OTG)**.

---

## Features

### Viewing
- Live ISDB-T TV playback (ExoPlayer / Media3) with fast channel start
- Hardware decoding via MediaCodec, with an **FFmpeg software fallback** for formats your device cannot decode
- Channel list with **virtual channel numbers**, station logos, and **favorites**
- **Auto Scan** (quick list or full UHF sweep, 473.143 to 695.143 MHz) and **Manual Scan** (MHz, kHz, or UHF channel number)
- **Electronic Program Guide (EPG)** from broadcast EIT data
- **Audio track and language selection**, with the best track auto-picked when no language is chosen
- **Closed captions** (ARIB), as a simple text line or styled, broadcast-layout captions
- **EWBS** (Emergency Warning Broadcast System) detection with an on-screen alert banner:
  - Detects the emergency start-up flag (TMCC / IIP), the PMT emergency information descriptor (`0xFC`), and BML event messages
  - Red **EWBS ACTIVE** banner while an alert is on, and an amber **EWBS was signalled** banner after it ends (so finished recordings still show it)
  - Works on the main screen and in the Analyzer, which also keeps a timestamped EWBS event log
- **Channel recording** to a transport stream file
- Portrait and landscape layouts for phones and tablets, plus a TV layout

### Data Broadcast (BML)
- Full ARIB data broadcast display over the video, with built-in BML sounds and fonts
- On-screen remote panel in portrait; D-pad, number, and colour-key support in landscape
- Keyboard and TV remote control support (see [Controls](#controls))

### Android TV / TV Boxes
- Registers an **OpenDTV ISDB-T TV input** for the system *Live Channels* app (Android 9+ / API 28+)
- Channel scan writes channels and EPG data to the TV provider

### Analyzer and Developer Tools
- Signal strength and quality bars with dBm readout
- Transport stream analyzer: PAT / PMT / SDT / NIT / EIT / TDT-TOT, PID statistics, CC / TEI / sync-loss counters
- DSM-CC data carousel inspector
- Open a `.ts` capture file to test and develop without a dongle
- USB probe diagnostic screen

---

## Getting Started

### Requirements
- Android 9 (API 28) or newer
- A device with USB Host (OTG) support and an OTG cable or adapter
- A compatible ISDB-T dongle (see above)
- An ISDB-T antenna and signal coverage in your area
- Recommended: a recent Android System WebView (for BML and styled captions)

### Installation
1. Download the latest APK from the [Releases](../../releases) page.
2. Plug the dongle into your device with an OTG adapter.
3. Open OpenDTV and allow the USB permission prompt.
4. Open the menu and choose **Auto Scan** to find channels.
5. Pick a channel and start watching.

### Using with Live Channels (Android TV / TV box)
1. Install the APK, plug in the dongle, and allow USB permission.
2. Open **Live Channels > Settings > Channel sources** (or the *OpenDTV channel scan* icon on the launcher).
3. Choose **OpenDTV ISDB-T** and run a **Quick scan** or **Full scan**.
4. Watch from Live Channels.

Notes: one tuner means one viewing session at a time. EPG times are based on the offset between the broadcast clock and your device clock, so make sure your device date and time are correct.

---

## Controls

Data broadcast controls (landscape):

| Action | Remote | Keyboard |
|---|---|---|
| Show / hide data broadcast | DATA key (or TEXT key) | F5 |
| Move | D-pad | Arrow keys or W A S D |
| Select | OK / Enter | Enter / numpad Enter |
| Back | BACK | F6 or Backspace |
| Colour keys | Red / Green / Yellow / Blue | F1 / F2 / F3 / F4 |
| Numbers | 0-9 | 0-9 and numpad 0-9 |
| Close | DATA again | F5 or Esc |

In portrait, use the floating remote button (bottom right) to open the on-screen remote panel.

---

## Project Structure

```
ph.opendtv          MainActivity, AnalyzerActivity, UsbProbeActivity
ph.opendtv.ts       Pure Java: TS demux, section assembler, service filter, carousel decoder, models
ph.opendtv.source   Tuner and TS sources: UsbTunerSource, It9175Tuner, FileTsSource
ph.opendtv.player   TsPipeDataSource (Media3)
ph.opendtv.bml      BML data broadcast controller, remote panel, sound player
ph.opendtv.tv       Live Channels TV input, session, hub, setup/scan
ph.opendtv.ui       Adapters, caption views, EWBS banner, logos
```

---

## Known Limitations

- **No descrambling.** No B-CAS code is included or planned, so encrypted services will not play.
- Virtual channel numbers come from the NIT `remote_control_key_id`. This is a heuristic and may need adjusting for the local channel plan.
- Broadcast times are shown as received. ARIB specifies JST (UTC+9), but local broadcasters may differ.
- MPEG-2 playback with hardware decoding depends on your device having an MPEG-2 decoder. Otherwise the FFmpeg fallback is used.
- Not ported from the vendor library: ALPU authentication, crystal indexes other than 0/1, and EEPROM-configured variants.
- Tuner support has been confirmed on a real PT275. Other listed dongles share the same chip but may behave differently.

---

## Contributing

Contributions are welcome. Bug reports, new dongle support, channel logos, and pull requests all help.

When reporting a tuner issue, please include the full report from the **USB Probe** screen and, if possible, a short `.ts` capture.

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes
4. Open a pull request

---

## Credits and Licenses

- **web-bml** by [otya128](https://github.com/otya128/web-bml) (MIT): BML data broadcast engine
- **aribb24.js**: ARIB caption rendering
- **NextLib** by [anilbeesetti](https://github.com/anilbeesetti/nextlib) (GPL-3.0): FFmpeg decoders for Media3
- **AndroidX Media3 / ExoPlayer** (Apache-2.0)
- **Kosugi / Kosugi Maru** fonts: BML fonts
- Other libraries: fflate, crc-32, fast-xml-parser, css (see the in-app *Open source licenses* screen)

OpenDTV's own code is released under the **MIT License**. See [`LICENSE`](LICENSE).

> **Note:** The app bundles NextLib (GPL-3.0) for FFmpeg decoding, which may affect how compiled builds can be redistributed. Review the licenses of all bundled components before distributing.
>
> OpenDTV contains no code or data from the vendor drivers (libpt275aDrv, GMA Now, Pad TV). The tuner logic was written from analysis of observable behavior.

---

## Disclaimer

OpenDTV is an independent project and is not affiliated with or endorsed by Mygica, GMA Network Inc., ITE Tech, or any broadcaster. All trademarks and station logos belong to their respective owners. Make sure you comply with the laws and broadcast regulations in your country.
