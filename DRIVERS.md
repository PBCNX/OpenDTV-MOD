# Tuner drivers are not in this fork

This tree carries the **seam**, not the drivers:

```text
in this repository        ph/opendtv/source/TunerSource.java
                          ph/opendtv/source/TunerProvider.java
                          ph/opendtv/source/TunerRegistry.java
not in this repository    every provider, every driver source, and every vendor library
```

So a build of this tree compiles, launches, and cannot drive a dongle. That is not a defect and not
a half-finished port — `TunerRegistry` loads providers by name and reports each absent one:

```text
TunerRegistry: driver not in this build: ph.opendtv.source.provider.SmsRioProvider
TunerRegistry: driver not in this build: ph.opendtv.source.provider.Sony9308Provider
```

## Where the drivers are

They live in the repositories that own them, as separate projects added through the seam:

```text
IT9175   https://github.com/PBCNX/it9175        MIT
SmsRio   https://github.com/PBCNX/smsrio        GPL-2.0
Sony 9308 / Endeavour                          private, vendor binaries, not published
```

To add one: implement `ph.opendtv.source.TunerProvider`, add its class name to
`TunerRegistry.OPTIONAL`, and it is picked up for a matching `UsbDevice` with no other change.

## This may change

The split exists while the driver repositories are moving. Once they are stable, whether a driver
ships in this tree is a decision to revisit rather than a rule — the seam supports either.

## Why it is this way

Vendor tuner libraries are proprietary, and several of the open ones are still being reworked. A
fork that compiles without any of them, and says precisely which driver is missing, is more useful
to hand over than one that carries half of them.
