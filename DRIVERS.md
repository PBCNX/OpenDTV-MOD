# Tuner drivers: two ship in this tree, the rest do not

This repository carries the seam AND the two MIT drivers that were written for it:

```text
in this repository     ph/opendtv/source/TunerSource.java      the seam a driver implements
                       ph/opendtv/source/TunerProvider.java
                       ph/opendtv/source/TunerRegistry.java    loads providers by class name
                       ph/opendtv/source/CleanRoom9175TunerSource.java  + provider/CleanRoom9175Provider
                       ph/opendtv/source/CleanRoom9308TunerSource.java  + provider/CleanRoom9308Provider
                       ph/pbcnx/it9175/**   the vendored MIT core the first one drives
                       ph/pbcnx/it9308/**   the vendored MIT core the second one drives
                       ph/opendtv/firmware/FirmwareStore.java  the firmware import path

not in this tree       SMS Rio (187f:0600)           GPL-2.0 source
                       DiBcom STK8096GP (10b8:1fa0)  GPL-2.0 source (a port of the Linux dib0700 driver)
                       every firmware image          vendor material, supplied by the user at runtime
                       IT9309 (048d:9309)            never supported: it needed the Sony/Endeavour vendor
                                                     binaries, removed from the PRIVATE tree on 2026-11-04
                                                     (clean-room core claims 048d:9308 first, so an 9308
                                                     never reached the vendor path)
```

The absent ones are absent for licence reasons, not because the seam cannot take them: `TunerRegistry`
loads every provider **by class name** and logs the ones a build does not carry. A GPL-2.0 driver
published next to MIT app code would make the whole app a combined work whose source has to be offered
under GPL-2.0, so those drivers live in their own repositories and their own builds.

## The two that do ship

```text
IT9175   048d:275a   MIT   pinned to PBCNX/it9175 v0.2.1, linked into this tree
IT9308   048d:9308   MIT   pinned to PBCNX/it9308 v0.2.1, linked into this tree
```

The 9308 clean-room core matches product id `0x9308` only. An IT9309 (`048d:9309`) is a different
product id and is NOT claimed by it: that stick needs the vendor path, which is not here.

## They need a firmware image, and this repository ships none

Both bridges start blank, so an image has to be uploaded into the stick every time it is plugged in.
Those images are vendor material. Nothing in this repository distributes them or grants any right to
them.

The app therefore asks for the image once, in **Settings, Tuner setup**, and checks it against the
SHA-256 of the image the driver was written for before it uploads a single byte. The two it can use:

```text
it9175-ite-v3046.bin   9,036 bytes   sha256 451b09a40d072d3e7c0b805f3d180a8c8e10456a3768cce0cc27510c05c3575c
it9308-sony-1.10.bin   6,498 bytes   sha256 e4e94633b4ac47c6bba5ab90eedd4a7e5ffa2ce7e342884c91749c7464704a70
```

A file whose name says one thing and whose bytes say another is identified by its bytes, and a file
that is neither is refused with its own hash in the message. A build of this tree compiles, starts and
reports that no firmware is installed until an image is imported - that is the intended first run, not
a defect.

## Adding another driver

Implement `com.pbcnx.opendtvmod.source.TunerProvider`, add its class name to `TunerRegistry.OPTIONAL`, and it is
picked up for a matching `UsbDevice`. Nothing else changes: that is why the absent ones are loaded
reflectively and logged rather than linked.
