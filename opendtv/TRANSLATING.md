# Translating OpenDTV-MOD

The app's screen text lives in `app/src/main/res/values/strings.xml` (English) and
`app/src/main/res/values-tl/strings.xml` (Tagalog). A string missing from a translation falls back
to English, so a new screen never breaks; it shows in English until its translation is added.

## How the language is picked

- **Settings > App language · Wika** (Tagalog: *Wika ng app · Language*) offers System default,
  English and Tagalog (`ui/watch/AppLanguage`). On Android 13 and up it is the same setting as the
  phone's Settings > Apps > OpenDTV-MOD > Language; on older phones AppCompat stores the choice
  (`autoStoreLocales` in the manifest) and applies it on start.
- **The phone's language.** A phone set to Filipino (`fil-PH`) loads `values-tl`, because Android
  treats `tl` and `fil` as one language.
- **Android 13 and up: the app's own language.** `res/xml/locales_config.xml` (the manifest's
  `android:localeConfig`) lists English and Filipino, so Settings > Apps > OpenDTV-MOD > Language
  offers them, whatever the phone's own language is.
- **Tagalog is requested as `fil`, but its folder is `values-tl`.** Both halves matter:
  - `values-tl` is what Android itself, AppCompat and Material use for Filipino.
  - Android puts the phone's languages after the app's (`[tl, en-US]`) and matches that list
    against the app's languages, where it reports `values-tl` as `fil`. A `tl` request never
    matches and the app stays in English (checked on the emulator with
    `cmd locale set-app-locales`). `locales_config.xml` lists `fil` for the same reason. Do not
    turn on AGP's `generateLocaleConfig`: it would write `tl`.
  - The system names the language "Filipino"; the app's own list calls it "Tagalog".
- Changing the language recreates the screen, the same way the decoder switch does.

To check a translation without tapping through Settings (the bench build is
`com.pbcnx.opendtvmod.ph`, the release `com.pbcnx.opendtvmod`):

```
adb shell cmd locale set-app-locales com.pbcnx.opendtvmod.ph --user 0 --locales fil
adb shell cmd locale set-app-locales com.pbcnx.opendtvmod.ph --user 0 --locales ""
```

## What stays in English, on purpose

- The Signal & TS Analyzer, the Developers screen and the USB probe: technical tools, and the
  analyzer colours its log by matching English words ("error", "no lock", ...). The diagnostics
  file they export stays in English too, so it reads the same whoever sends it.
- The Open source licenses screen: licence names and texts.
- Log lines, and the messages code compares by text: TunerHub's failure messages and the data
  broadcast status that MainActivity filters for "error".
- Error details that come from below the screens: the tuner drivers, the vendor image store and
  the channel-file parser. A translated sentence carries them as they are ("Hindi ma-lock ang
  497.143 MHz - No lock on 497143 kHz").
- Key and chip labels printed that way on remotes and in broadcasts: DATA, BACK, OK, CC, REC,
  LOCK, EWBS, EXIT BML. Their TalkBack descriptions are translated.
- Channel names, area names (EwbsAreaTable), device names (IT9175, IT9308, LME2510) and
  everything the broadcast sends.
- The toolbar title: it is the project's name.

## Rules for Tagalog

They follow how Google translates Android and its TV apps into Filipino (AOSP `values-tl`,
including Android 8.1's USB TV tuner app), the Microsoft Filipino style guide and the KWF
*Ortograpiyang Pambansa*.

1. **Plain Taglish, the way phones already talk.** Keep the technical nouns people say in
   English: channel, signal, scan, tuner, dongle, stick, antenna, frequency, caption, record,
   storage, data broadcast, decoder, driver, vendor image, stream, folder, file, app, device, USB,
   EPG, EWBS. Use Tagalog for the verbs and the everyday words: *Pumili, Isara, Bumalik,
   Kanselahin, Ihinto, Payagan, Alisin, Wika, Lokasyon, Bersyon*.
2. **Address the viewer as *mo / ka / iyong*.** Never *po*, *kayo* or *ninyo* in the UI.
3. **Hyphenate a Filipino affix on an English word:** *i-scan, mag-scan, pag-scan, na-scan,
   i-record, naka-lock, naka-encrypt, i-tap, ma-unlock, i-import, na-install*.
4. **Repeat only the first consonant and vowel for the progressive:** *nagsa-scan, nagtu-tune,
   nagre-record, nire-record, nagpe-play, sine-save, nilo-load, naa-upload*; future *isa-scan*.
   Not *nag-i-scan*, *ii-scan*.
5. **Buttons and instructions:** a bare imperative. *I-scan ang ...* for a definite thing,
   *Mag-scan ng ...* for an indefinite one, *Pindutin ang ...* for a key. Prefer *para, puwede,
   pero, dahil* over *upang, maaari, ngunit, sapagkat*. Keep a button about as long as the
   English: three buttons in a row share the width, so a longer label is cut off (*I-export*,
   not *I-export ang file*).
6. **Plurals take *mga*, never an English -s:** *Mga Channel*, *mga caption*. One-word titles
   capitalize the noun (*Mga Setting*); longer ones don't (*Mga open source na lisensya*).
7. **Never *mga* after a number** (*mga 5* means "about 5"). Counts use `<plurals>` with exactly
   `one` and `other`: `one` = "%d channel", `other` = "%d na channel". `one` is not "singular":
   it is every number said with *-ng* (0, 1, 2, 3, 5, 7, 8, 10...), and `other` the ones that end
   in 4, 6 or 9 (*apat na, anim na, siyam na*). So never write *isang* in `one`.
8. **Errors:** *Walang nakitang ...* (not found), *Hindi ma-...* (can't), *Hindi na-...*
   (failed), *Hindi sinusuportahan* (not supported), *Nagka-error sa ...* (something failed).
9. **Sentence case**, like the English. Section headers are all capitals (*MGA CAPTION*).
10. **Keep the placeholders** (`%1$s`, `%2$d`, `%d`) and the punctuation the English has (`…`,
    `·`, `...`, `-`, `\n`). Strings with two spaces in a row stay in double quotes.
11. **A toast shows two lines at most** on Android 12 and up (about 75 characters on a phone) and
    cuts the rest with "…". Keep a toast's Tagalog about as long as the English, and look at a long
    one on a phone.

## Word list

| English | Tagalog |
|---|---|
| Settings / Channels / Favorites | Mga Setting / Mga Channel / Mga Paborito |
| Cancel / Apply / Close / Back / Stop / Done | Kanselahin / Ilapat / Isara / Bumalik / Ihinto / Tapos na |
| Merge / Replace | Pagsamahin / Palitan |
| Remove / Keep | Alisin / Panatilihin |
| Export file / Import file (channel list) | I-export / I-import |
| Import folder / Import file (tuner setup) | Mula sa folder / Mula sa file |
| Scan (button) / scan (noun) | I-scan / pag-scan |
| Scanning… / Tuning… / Opening… / Playing | Nagsa-scan… / Nagtu-tune… / Binubuksan… / Nagpe-play |
| Auto Channel Scan / Manual Frequency Scan | Awtomatikong pag-scan ng channel / Manual na pag-scan ng frequency |
| Full scan / Quick scan | Buong pag-scan / Mabilis na pag-scan |
| %d channels found / %d services found | Nakahanap ng %d channel / Nakahanap ng %d serbisyo |
| Scan complete / Scan stopped | Tapos na ang pag-scan / Itinigil ang pag-scan |
| Program guide / EPG | Gabay sa programa / EPG |
| Now / Next | Ngayon / Susunod |
| No program information / (no title) | Walang impormasyon ng programa / (walang pamagat) |
| No signal / Weak signal | Walang signal / Mahinang signal |
| Signal strength / quality | Lakas / Kalidad (ng signal) |
| Locked / Unlocked / No lock | Naka-lock / Hindi naka-lock / Walang lock |
| Tuner not found / Plug in the USB dongle | Walang nakitang tuner / Isaksak ang USB dongle |
| Plugged in / Not plugged / Ready | Nakasaksak / Hindi nakasaksak / Handa |
| Tuner setup / vendor image | Setup ng tuner / vendor image |
| Scrambled / Encrypted | Naka-scramble / Naka-encrypt |
| No picture | Walang larawan |
| Record / Recording / Stop recording | I-record / Nagre-record / Ihinto ang pag-record |
| Captions / Closed captions | Mga caption / Mga closed caption |
| Audio language / Caption language | Wika ng audio / Wika ng caption |
| Video decoder / the device's decoder | Decoder ng video / ang decoder ng device |
| Automatic / Manual | Awtomatiko / Manual na ... |
| Aspect ratio / Stretch / Fit / Zoom / Force 4:3 | Aspect ratio / I-stretch / Ipagkasya / Mag-zoom / Ipilit ang 4:3 |
| Picture & sound | Larawan at tunog |
| Location / Region / City / Zone | Lokasyon / Rehiyon / Lungsod / Zone |
| Emergency alerts / Emergency warning | Mga alertong pang-emergency / Babala sa emergency |
| Permission / Allow / Deny | Pahintulot / Payagan / Tanggihan |
| On / Off (state) | Naka-on / Naka-off |
| Unknown channel | Hindi kilalang channel |
| About / Version / Open source licenses | Tungkol dito / Bersyon / Mga open source na lisensya |
| Advanced / Developer options | Advanced / Developer options (that screen is in English) |
| Details / Hide details | Mga detalye / Itago ang mga detalye |
| tap to change | i-tap para baguhin |

## Adding a screen, or a language

1. Put the English in `values/strings.xml`, grouped under the screen's comment, and read it with
   `getString(R.string.x)` in an activity, `activity.getString(...)` in a controller,
   `getContext().getString(...)` in a view, or `@string/x` in a layout. Counts use
   `getResources().getQuantityString(R.plurals.x, n, n)`.
2. Add the same key to `values-tl/strings.xml` with the rules above. A key that exists only in a
   translation fails the release build (lint ExtraTranslation is fatal); a key missing from it
   only shows English.
3. A new language: a `values-xx` folder, one `<locale>` line in `res/xml/locales_config.xml`, and
   a row in `AppLanguage` (`TAGS` and `labels`).
