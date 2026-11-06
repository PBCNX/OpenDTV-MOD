# OpenDTV-MOD

Watch and analyze Philippine free-to-air digital TV (ISDB-T) on an Android phone or tablet with a
USB tuner dongle.

A fork of [jpdcs/OpenDTV](https://github.com/jpdcs/OpenDTV) (MIT).

## What it does

- Live TV with captions, full-screen video, and channel browsing
- Signal and transport-stream analyzer: strength, quality, bitrate, PIDs, tables, EPG
- BML data-broadcast pages (the engine is bundled)
- Records a channel, or a whole multiplex, to a `.ts` file
- EWBS emergency alerts, filtered by the Philippine area you choose
- UHF scan that can merge into your list or replace it
- Works with two easy-to-find USB sticks: **IT9175** and **IT9308**

## What you need

```text
Android 5.0 or newer, with USB host (OTG)
A supported dongle: IT9175 (048d:275a) or IT9308 (048d:9308)
An antenna
The stick's firmware image, supplied by you once (Settings -> Tuner setup). It is checked by
SHA-256 before it is used; none is distributed with this app.
```

Not supported in this tree: Siano Rio and DiBcom sticks (their drivers are GPL-2.0 and stay outside
this MIT source; see `DRIVERS.md`), and TV boxes are untested.

## Build

```sh
# JDK 17, Gradle 8.11.1
cd opendtv
gradle :app:assembleDebug
```

Release builds are signed only when `../keys/keystore.properties` exists; without it you get an
unsigned APK. Keys are never committed.

## Licence

Our own code is MIT (`LICENSE`). The bundled data-broadcast engine is MIT, the BML fonts are under
the SIL Open Font License, and the software decoders are GPL-3.0. Full texts are in the app under
**Settings -> About -> Open source licenses**, and also in `opendtv/app/src/main/assets/licenses/`.
`DRIVERS.md` explains exactly what is and is not in this source tree.
