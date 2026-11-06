# Translating OpenDTV-MOD

The app's screen text lives in `app/src/main/res/values/strings.xml` (English) and
`app/src/main/res/values-tl/strings.xml` (Tagalog). A string missing from a translation falls back
to English, so a new screen never breaks; it shows in English until its translation is added.

## How the language is picked

- **Settings > App language · Wika** (Tagalog: *Wika ng app · Language*) offers System default,
  English and Tagalog. On Android 13 and up it is the same setting as the phone's
  Settings > Apps > OpenDTV-MOD > Language; on older phones AppCompat stores the choice
  (`autoStoreLocales` in the manifest) and applies it on start.
- **Tagalog is requested as `fil`, but its folder is `values-tl`.** Both halves matter:
  - `values-tl` is what Android itself, AppCompat and Material use for Filipino, and a phone set
    to Filipino (`fil-PH`) loads it, because Android treats `tl` and `fil` as one language.
  - The app asks for `fil` because Android puts the phone's languages after the app's
    (`[tl, en-US]`) and matches that list against the app's languages, where it reports
    `values-tl` as `fil`. A `tl` request never matches and the app stays in English (checked on
    the emulator with `cmd locale set-app-locales`). `res/xml/locales_config.xml` lists `fil` for
    the same reason. Do not turn on AGP's `generateLocaleConfig`: it would write `tl`.
  - The system names the language "Filipino"; the app's own list calls it "Tagalog".
- Changing the language recreates the screen, the same way the decoder switch does.

To check a translation without tapping through Settings:

```
adb shell cmd locale set-app-locales com.pbcnx.opendtvmod.ph --user 0 --locales fil
adb shell cmd locale set-app-locales com.pbcnx.opendtvmod.ph --user 0 --locales ""
```

## What stays in English, on purpose

- The Signal & TS Analyzer, the Developers screen and the USB probe: technical tools, and the
  analyzer colours its log by matching English words ("error", "no lock", ...).
- The licence texts, log lines, and messages that code compares by text (TunerHub's
  `lastMessage()`, the data broadcast status that MainActivity filters for "error").
- Key and chip labels printed that way on remotes and in broadcasts: DATA, BACK, OK, CC, REC,
  LOCK, EWBS. Their TalkBack descriptions are translated.
- Channel names, area names (EwbsAreaTable) and everything the broadcast sends.

## Rules for Tagalog

They follow how Google translates Android and its TV apps into Filipino (AOSP `values-tl`,
including Android 8.1's USB TV tuner app), the Microsoft Filipino style guide and the KWF
*Ortograpiyang Pambansa*.

1. **Plain Taglish, the way phones already talk.** Keep the technical nouns people say in
   English: channel, signal, scan, tuner, dongle, antenna, frequency, caption, record, storage,
   data broadcast, decoder, stream, folder, app, device, USB, EPG, EWBS. Use Tagalog for the
   verbs and the everyday words: *Pumili, Isara, Bumalik, Kanselahin, Ihinto, Payagan, Wika,
   Lokasyon, Bersyon*.
2. **Address the viewer as *mo / ka / iyong*.** Never *po*, *kayo* or *ninyo* in the UI.
3. **Hyphenate a Filipino affix on an English word:** *i-scan, mag-scan, pag-scan, na-scan,
   i-record, naka-lock, naka-encrypt, i-tap, ma-unlock*.
4. **Repeat only the first consonant and vowel for the progressive:** *nagsa-scan, nagtu-tune,
   nagre-record, nire-record, sine-save, nilo-load*; future *isa-scan*. Not *nag-i-scan*,
   *ii-scan*.
5. **Buttons and instructions:** a bare imperative. *I-scan ang ...* for a definite thing,
   *Mag-scan ng ...* for an indefinite one, *Pindutin ang ...* for a key. Prefer *para, puwede,
   pero, dahil* over *upang, maaari, ngunit, sapagkat*.
6. **Plurals take *mga*, never an English -s:** *Mga Channel*, *mga caption*. One-word titles
   capitalize the noun (*Mga Setting*); longer ones don't (*Mga open source na lisensya*).
7. **Never *mga* after a number** (*mga 5* means "about 5"). Counts use `<plurals>` with exactly
   `one` and `other`: `one` = "%d channel", `other` = "%d na channel". `one` is not "singular":
   it is every number said with *-ng* (0, 1, 2, 3, 5, 7, 8, 10...), and `other` the ones that end
   in 4, 6 or 9 (*apat na, anim na, siyam na*). So never write *isang* in `one`.
8. **Errors:** *Walang nakitang ...* (not found), *Hindi ma-...* (can't), *Hindi na-...*
   (failed), *Hindi sinusuportahan* (not supported).
9. **Sentence case**, like the English. Section headers are all capitals (*MGA CAPTION*).
10. **Keep the placeholders** (`%1$s`, `%2$d`, `%d`) and the punctuation the English has (`…`,
    `·`, `...`, `\n`). Strings with two spaces in a row stay in double quotes.

## Word list

| English | Tagalog |
|---|---|
| Settings / Channels / Favorites | Mga Setting / Mga Channel / Mga Paborito |
| Cancel / Apply / Close / Back / Stop / Done | Kanselahin / Ilapat / Isara / Bumalik / Ihinto / Tapos na |
| Scan (button) / scan (noun) | I-scan / pag-scan |
| Scanning… / Tuning… | Nagsa-scan… / Nagtu-tune… |
| Auto Channel Scan / Manual scan | Awtomatikong pag-scan ng channel / Manual na pag-scan |
| Full scan / Quick scan | Buong pag-scan / Mabilis na pag-scan |
| %d channels found | Nakahanap ng %d channel / na channel |
| Program guide / EPG | Gabay sa programa / EPG |
| Now / Next | Ngayon / Susunod |
| No program information / (no title) | Walang impormasyon ng programa / (walang pamagat) |
| No signal / Weak signal | Walang signal / Mahinang signal |
| Signal strength / quality | Lakas / Kalidad (ng signal) |
| Locked / Unlocked / No lock | Naka-lock / Hindi naka-lock / Walang lock |
| Tuner not found / Plug in the USB dongle | Walang nakitang tuner / Isaksak ang USB dongle |
| Scrambled / Encrypted | Naka-scramble / Naka-encrypt |
| No picture | Walang larawan |
| Record / Recording / Stop recording | I-record / Nagre-record / Ihinto ang pag-record |
| Captions / Closed captions | Mga caption / Mga closed caption |
| Audio language / Caption language / App language | Wika ng audio / Wika ng caption / Wika ng app |
| Automatic / System default | Awtomatiko / Default ng system |
| Aspect ratio / Stretch / Fit / Zoom | Aspect ratio / I-stretch / Ipagkasya / Mag-zoom |
| Picture & sound | Larawan at tunog |
| Location / Region / City / Zone | Lokasyon / Rehiyon / Lungsod / Zone |
| Emergency alert / Show all alerts | Alertong pang-emergency / Ipakita lahat ng alerto |
| Permission / Allow / Deny | Pahintulot / Payagan / Tanggihan |
| On / Off (state) | Naka-on / Naka-off |
| Unknown channel | Hindi kilalang channel |
| About / Version / Open source licenses | Tungkol dito / Bersyon / Mga open source na lisensya |
| Advanced / Developer options | Advanced / Developer options (that screen is in English) |
| Details / Hide details | Mga detalye / Itago ang mga detalye |
| tap to change | i-tap para baguhin |

## Adding a screen, or a language

1. Put the English in `values/strings.xml`, grouped under the screen's comment, and read it with
   `getString(R.string.x)`, `getContext().getString(...)` in a view, or `@string/x` in a layout.
   Counts use `getResources().getQuantityString(R.plurals.x, n, n)`.
2. Add the same key to `values-tl/strings.xml` with the rules above. A key that exists only in a
   translation fails the release build (lint ExtraTranslation is fatal); a key missing from it
   only shows English.
3. A new language: a `values-xx` folder, one `<locale>` line in `res/xml/locales_config.xml`, and
   an entry in MainActivity's `APP_LANGUAGE_TAGS` and its list.
