/*
 * The BML host: the adapter between this app and the web-bml engine.
 *
 * It was never source. It lived inside a 1.4 MB bundle that was uploaded to this repository once
 * ("Add files via upload") and never rebuilt, which is why every engine improvement we made existed
 * in a repository and in no APK. This file is that adapter, lifted out of the bundle verbatim, so
 * the engine can be a declared dependency and the asset rebuilt from things that are in the repo.
 *
 * What it does:
 *   - builds the engine's BMLBrowser with THIS app's policy: our fonts, video-plane mode, and stubs
 *     for the parts of the standard we do not implement (no return channel, no EPG, no indicator)
 *   - receives TS packets from Android as base64, demuxes them with the engine's decodeTS, and
 *     feeds the resulting messages to the browser
 *   - reports back what Android must know: that the page loaded (resolution + profile), where the
 *     page put the video plane, and whether it asked for video to be hidden
 *   - rescales the 720x480 BML logical screen onto whatever display it runs on
 *   - maps ARIB key codes from Android into the engine's content
 *
 * Contract with Java - the only two names that cross the WebView boundary:
 *   window.OpenDtvBml = { start(serviceId), push(base64Ts), key(code, down), tap(code), codes }
 *   AndroidBml        = { log(msg), onLoad(w, h), onVideo(l, t, r, b, invisible), onState(json) }
 *                       (injected by com.pbcnx.opendtvmod.bml.BmlController; this file only calls it)
 *
 * Engine: web-bml 3.0.1, vendored under vendor/web-bml and pinned in engine.lock.json (public: github.com/PBCNX/web-bml-PHL).
 * Build:  node tools/bml/build.mjs   ->  app/src/main/assets/bml/bml.js
 */
'use strict';

const { BMLBrowser, AribKeyCode } = require('./vendor/web-bml/client/bml_browser.js');
const { decodeTS } = require('./vendor/web-bml/server/decode_ts.js');

(function () {
  function native(f) {
    try {
      f();
    } catch (e) {
    }
  }
  var stage = document.getElementById("stage");
  var contentEl = document.getElementById("content");
  var mediaEl = document.getElementById("media");
  var resW = 720;
  var resH = 480;
  function rescale() {
    const sx = window.innerWidth / resW, sy = window.innerHeight / resH;
    stage.style.width = resW + "px";
    stage.style.height = resH + "px";
    stage.style.transform = `scale(${sx}, ${sy})`;
  }
  window.addEventListener("resize", rescale);
  var invisible = false;
  var lastVideo = { l: 0, t: 0, r: 720, b: 480 };
  function reportVideo() {
    native(() => AndroidBml.onVideo(lastVideo.l / resW, lastVideo.t / resH, lastVideo.r / resW, lastVideo.b / resH, invisible));
  }
  var bml = new BMLBrowser({
    containerElement: contentEl,
    mediaElement: mediaEl,
    tabIndex: 0,
    // The three gothic slots, all on BML-PH Sans since the engine's decision of 2026-11-02.
    // All SIL OFL 1.1; paths are relative to bml/index.html.
    fonts: {
      // web-bml 2.1.0 replaced the font stack: BML-PH Sans is derived from Noto Sans Mono CJK JP
      // with the BML cell enforced on every Latin letter (advance 0.5 em, accents matched to
      // their base width) and kana/kanji/U+3000 at 1 em, so the unicodeRange workaround for
      // U+3000 is gone - the face draws it at 1 em itself. The M+ and Kosugi faces this host
      // used to register are deleted in 3.0.0; these are the files that replace them.
      //
      // ALL SIX slots point at Sans, which is the engine session's 2026-11-02 decision and the
      // reason it matters: the spec's initial font-family is the round gothic slot, most PH pages
      // name no family at all, and a page naming the square gothic slot would otherwise show two
      // looks at once. The rounded face (BMLPH-RoundGothic-Regular.woff2) is a LOOK, not an
      // alternative default - the JP build takes it deliberately (owner: PH uniform, JP rounded).
      // Kosugi went with 3.0.0 AND with the caption surface on 2026-11-04: captions.html draws with
      // this same BML-PH Sans face now, so no build ships an orphan 1.1 MB face for one surface.
      roundGothic: { source: "url('fonts/BMLPH-Sans-Regular.woff2')" },
      boldRoundGothic: { source: "url('fonts/BMLPH-Sans-Bold.woff2')" },
      squareGothic: { source: "url('fonts/BMLPH-Sans-Regular.woff2')" },
      // No phLatin slot: BML-PH Sans already enforces the half-em cell for Latin, and after
      // 3.0.0 the slot ships no default file. `fallback` covers anything left.
      fallback: { source: "url('fonts/BMLPH-Sans-Regular.woff2')" },
      lastResort: { source: "url('fonts/BMLPH-Sans-Regular.woff2')" }
    },
    // C16: the broadcast local-time offset from UTC, in minutes. The engine used to read the TOT
    // descriptor and "correct" by it; 2.0.4 reports the field verbatim and takes the zone from the
    // host instead. 480 is the Philippine box: the ABS-CBN TVplus ignores the descriptor and adds
    // eight hours to whatever the field holds, so the page must do the same or it disagrees with the
    // receiver the viewer is watching (BMLPLAYER's eight-mux sweep, PBCNX-DTV's TimeInjector).
    // Japan's build passes 540 here.
    timeZoneOffsetMinutes: 480,
    videoPlaneModeEnabled: true,
    ip: {},
    epg: { tune() {
      return false;
    } },
    indicator: {
      setUrl(name, loading) {
      },
      setReceivingStatus(r) {
      },
      setNetworkingGetStatus() {
      },
      setNetworkingPostStatus() {
      },
      setEventName() {
      }
    },
    log: { level: "warn" },
    showErrorMessage(title, message, code) {
      native(() => AndroidBml.onState(JSON.stringify({ error: title + ": " + message + (code ? " (" + code + ")" : "") })));
    }
  });
  bml.addEventListener("load", (evt) => {
    resW = evt.detail.resolution.width;
    resH = evt.detail.resolution.height;
    rescale();
    native(() => AndroidBml.onLoad(resW, resH));
    native(() => AndroidBml.log("BML loaded " + resW + "x" + resH + " profile=" + evt.detail.profile));
  });
  bml.addEventListener("invisible", (evt) => {
    invisible = !!evt.detail;
    contentEl.style.visibility = invisible ? "hidden" : "";
    reportVideo();
  });
  bml.addEventListener("videochanged", (evt) => {
    const r = evt.detail.clientRect;
    lastVideo = { l: r.left, t: r.top, r: r.right, b: r.bottom };
    reportVideo();
  });
  var reader = null;
  var seenPmt = false;
  function b64ToBytes(s) {
    const bin = atob(s);
    const out = new Uint8Array(bin.length);
    for (let i2 = 0; i2 < bin.length; i2++) out[i2] = bin.charCodeAt(i2);
    return out;
  }
  /**
   * 2.6.0: write the WHOLE receiver-area set (regioncode, prefecture 255, zipcode empty) before the
   * page reads it, so a PH code can never sit beside a leftover Japanese postal code. 0 and null mean
   * "unset" and clear it. An engine without the setter leaves the page seeing null, which is what unset
   * means anyway - so this degrades to the old behaviour instead of throwing.
   */
  function applyAreaCode(areaCode) {
    var code = (areaCode === null || areaCode === undefined || areaCode === 0) ? null : areaCode;
    try {
      if (bml && typeof bml.setReceiverAreaCode === "function") {
        var changed = bml.setReceiverAreaCode(code);
        native(() => AndroidBml.log("receiver area " + (code === null ? "unset" : "0x" + code.toString(16)) + (changed ? " written" : " unchanged")));
        return changed;
      }
      native(() => AndroidBml.log("receiver area: engine has no setReceiverAreaCode"));
    } catch (e) {
      native(() => AndroidBml.log("receiver area failed: " + (e && e.message ? e.message : e)));
    }
    return false;
  }

  var api = {
    /** serviceId: the service whose data broadcast to show (program_number). */
    start(serviceId, areaCode) {
      applyAreaCode(areaCode);   // before the page loads: it may read the area on first script
      reader = decodeTS({
        serviceId,
        parsePES: false,
        sendCallback: (msg) => {
          if (msg.type === "pmt" && !seenPmt) {
            seenPmt = true;
            native(() => AndroidBml.log("PMT: " + msg.components.length + " components"));
          }
          bml.emitMessage(msg);
        }
      });
      rescale();
    },
      /** 2.6.0 live update: the viewer changed their region while a page was already running. */
      setArea(areaCode) {
        return applyAreaCode(areaCode);
      },

    /** Base64 of whole 188-byte TS packets (data PIDs + PAT/PMT/EIT/TDT only). */
    push(b64) {
      if (reader == null) return;
      reader.push(b64ToBytes(b64));
    },
    key(code, down) {
      if (down) bml.content.processKeyDown(code);
      else bml.content.processKeyUp(code);
    },
    /** press + release */
    tap(code) {
      bml.content.processKeyDown(code);
      bml.content.processKeyUp(code);
    },
    /**
     * P1: the engine's own diagnostics snapshot - phase, what is pending and why, locks, tables,
     * script errors, stream errors, and the configuration it runs with. Plain JSON by design, so a
     * host can log it, show it, or send it back from a tester's bench.
     */
    diagnostics() {
      try {
        return bml.getDiagnostics();
      } catch (e) {
        return { error: String(e) };
      }
    },
    codes: AribKeyCode
  };
  window.OpenDtvBml = api;
  window.addEventListener("pagehide", () => {
    try {
      bml.destroy();
    } catch (e) {
    }
  });
  rescale();
  native(() => AndroidBml.log("OpenDTV BML ready"));
})();
