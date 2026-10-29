# OpenDTV-MOD

ISDB-T analyzer and live viewer for **Philippine free-to-air**, on a USB tuner dongle.
A fork of [jpdcs/OpenDTV](https://github.com/jpdcs/OpenDTV) (MIT), taken from commit `5cf816f`.

## What this fork changes

```text
tuner seam         TunerSource / TunerProvider / TunerRegistry. Providers are loaded BY NAME, so a
                   build carries only the drivers it may: absent ones log "driver not in this
                   build" instead of failing (see TunerRegistry.OPTIONAL)
new tuner support  through the seam, in separate repositories: IT9175 (PBCNX/it9175, MIT) and
                   SmsRio (PBCNX/smsrio, GPL-2.0). Drop a provider in and the registry picks it
                   up for a matching UsbDevice; with none present the app says so per driver
                   rather than failing to start
PH specifics       EWBS area table and location filter for Philippine broadcasts
analyzer           told the frequency that is playing, and exactly one owner of the dongle across
                   the main-screen <-> analyzer hand-off
UI                 the browse tabs, watch surfaces, Settings, the About box, the Developers screen,
                   and the licences screen
quality            a gate that runs PMD 6.55 with a defect-only ruleset (tools/)
```

## Build

```sh
# JDK 17, Gradle 8.11.1
cd opendtv
gradle :app:assembleDebug
```

Release builds are signed only when a `keystore.properties` exists outside the repository
(`../keys/keystore.properties`, or `-Popendtv.keys.dir`). With none, `:app:assembleRelease`
produces an **unsigned** APK — never a failed build. Keys are never committed.

`opendtv/app/src/main/assets/bml/bml.js` is committed as built — the data-broadcast engine (MIT,
from `PBCNX/web-bml-PHL` at `faf4d96`), built with host glue that is not in this repository. It
builds as-is; it is not regenerated from this tree.

## Version

`0.1.0-beta`, tag `v0.1.0-beta`, versionCode `1002`. The counter and the name live in
`opendtv/version.properties` and are written only by a release; see `docs/VERSIONING.md`.

## Licences

In the app: **Settings → About → Open source licenses**, with the full texts in
`opendtv/app/src/main/assets/licenses/`. Our own code is MIT (`LICENSE`).
