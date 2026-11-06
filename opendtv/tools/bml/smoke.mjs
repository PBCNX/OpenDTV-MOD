/*
 * smoke.mjs - run the built BML bundle against a real transport stream, headless.
 *
 *   node smoke.mjs <file.ts> [megabytes]
 *
 * Why: there is no box, no phone and no BML on air, so "does the new bundle actually run" cannot be
 * answered on hardware. A capture can: parse TS -> find the service -> feed the same base64 packets
 * the app feeds -> collect what the bundle reports through the AndroidBml bridge it expects.
 *
 * It is not a WebView: jsdom supplies the DOM, so page *rendering* is not proven. What it does prove
 * is that the bundle parses, initialises, takes TS through the engine's demuxer, and reaches a BML
 * page load - the parts that break when an engine is swapped.
 */
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appRoot = join(here, '..', '..');
const bundlePath = join(appRoot, 'app', 'src', 'main', 'assets', 'bml', process.env.BML_BUNDLE || 'bml.js');
const tsPath = process.argv[2];
const limitMB = Number(process.argv[3] || 24);
if (!tsPath) { console.error('usage: node smoke.mjs <file.ts> [megabytes]'); process.exit(2); }

const PACKET = 188;

function parsePsi(buf, tableIdWanted) {
  // walk packets, collect sections with pointer_field handling kept simple: one section per packet
  const out = [];
  for (let off = 0; off + PACKET <= buf.length; off += PACKET) {
    if (buf[off] !== 0x47) continue;
    const pusi = (buf[off + 1] & 0x40) !== 0;
    const pid = ((buf[off + 1] & 0x1f) << 8) | buf[off + 2];
    const afc = (buf[off + 3] >> 4) & 3;
    if (!pusi || (afc & 1) === 0) continue;
    let p = off + 4;
    if (afc === 3) p += 1 + buf[p];
    if (p >= off + PACKET) continue;
    const pointer = buf[p];
    const s = p + 1 + pointer;
    if (s + 3 > off + PACKET || buf[s] !== tableIdWanted) continue;
    const len = (((buf[s + 1] & 0x0f) << 8) | buf[s + 2]);
    if (s + 3 + len > off + PACKET) continue;
    out.push({ pid, section: buf.subarray(s, s + 3 + len) });
  }
  return out;
}

function patPrograms(buf) {
  for (const { section } of parsePsi(buf, 0x00)) {
    const programs = [];
    for (let i = 8; i + 4 <= section.length - 4; i += 4) {
      const num = (section[i] << 8) | section[i + 1];
      const pid = ((section[i + 2] & 0x1f) << 8) | section[i + 3];
      if (num !== 0) programs.push({ num, pid });
    }
    if (programs.length) return programs;
  }
  return [];
}

function pmtInfo(buf, pmtPid) {
  for (const { pid, section } of parsePsi(buf, 0x02)) {
    if (pid !== pmtPid) continue;
    const types = [];
    for (let i = 12; i + 5 <= section.length - 4; i += 5) {
      types.push(section[i]);
    }
    return types;
  }
  return null;
}

const raw = readFileSync(tsPath).subarray(0, limitMB * 1024 * 1024);
const size = raw.length - (raw.length % PACKET);
console.log(`${tsPath}: ${(size / 1e6).toFixed(1)} MB, ${size / PACKET} packets`);

const programs = patPrograms(raw);
if (!programs.length) { console.error('no PAT found'); process.exit(1); }
console.log(`programs: ${programs.map((p) => `${p.num}/pmt ${p.pid}`).join(', ')}`);

let chosen = programs[0];
for (const p of programs) {
  const types = pmtInfo(raw, p.pid);
  if (!types) continue;
  console.log(`  program ${p.num}: pmt pid ${p.pid}, stream types ${types.map((t) => '0x' + t.toString(16)).join(' ')}`);
  // a data-broadcast component is the interesting one: 0x0d (DSM-CC) or 0x06 (private PES)
  if (types.some((t) => t === 0x0d || t === 0x06)) chosen = p;
}
console.log(`service under test: ${chosen.num}`);

// --- the page, and the bridge Java would inject ---------------------------------------------
const dom = new JSDOM(`<!doctype html><html><body>
  <div id="stage"><div id="content"></div><div id="media"></div></div></body></html>`,
  { url: 'http://localhost/bml/', pretendToBeVisual: true, runScripts: 'outside-only' });

const logs = [];
const state = { loaded: null, video: null, errors: [] };
const w = dom.window;
w.AndroidBml = {
  log: (m) => { logs.push(String(m)); if (logs.length <= 40) console.log('  [bml]', m); },
  onLoad: (width, height) => { state.loaded = { width, height }; console.log(`  [onLoad] ${width}x${height}`); },
  onVideo: (l, t, r, b, invisible) => { state.video = { l, t, r, b, invisible }; },
  onState: (json) => { state.errors.push(json); console.log('  [onState]', json); },
};
w.FontFace = w.FontFace || class { constructor() {} load() { return Promise.resolve(this); } };
// The browser pieces a headless DOM does not have. Each one is a no-op that keeps the page's own
// JavaScript alive; none of them affects what this smoke test is for (TS in, page load out).
const noop = () => {};
const node = () => ({ connect: noop, disconnect: noop, start: noop, stop: noop,
  gain: { value: 0, setValueAtTime: noop, linearRampToValueAtTime: noop },
  buffer: null, onended: null, frequency: { value: 0, setValueAtTime: noop } });
w.AudioContext = w.webkitAudioContext = class {
  constructor() { this.destination = {}; this.currentTime = 0; this.sampleRate = 48000; }
  createGain() { return node(); }
  createBufferSource() { return node(); }
  createBuffer() { return { getChannelData: () => new Float32Array(1) }; }
  decodeAudioData(d, ok) { const b = { getChannelData: () => new Float32Array(1), duration: 0 }; if (ok) ok(b); return Promise.resolve(b); }
  resume() { return Promise.resolve(); }
  close() { return Promise.resolve(); }
};
w.HTMLMediaElement.prototype.play = () => Promise.resolve();
w.HTMLMediaElement.prototype.pause = noop;
w.HTMLMediaElement.prototype.load = noop;
w.HTMLCanvasElement.prototype.getContext = () => null;
w.URL.createObjectURL = () => 'blob:stub';
w.URL.revokeObjectURL = noop;
// FontFaceSet: the engine registers the ARIB gothic slots on it.
w.document.fonts = w.document.fonts || { add: noop, delete: noop, has: () => false,
  check: () => true, forEach: noop, ready: Promise.resolve() };
w.matchMedia = w.matchMedia || (() => ({ matches: false, addEventListener: noop,
  removeEventListener: noop, addListener: noop, removeListener: noop }));
w.HTMLVideoElement.prototype.play = () => Promise.resolve();
w.HTMLVideoElement.prototype.load = noop;
  // localStorage: the engine keeps NVRAM there (receiver-area set included), so a harness without it
  // silently loses every write. A Map is enough for the get/set/remove the engine uses.
  if (!w.localStorage) {
    const store = new Map();
    w.localStorage = {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => { store.set(k, String(v)); },
      removeItem: (k) => { store.delete(k); },
      clear: () => { store.clear(); },
      key: (i) => Array.from(store.keys())[i] ?? null,
      get length() { return store.size; }
    };
  }

try {
  const bundle = readFileSync(bundlePath, 'utf8');
  w.eval(bundle);
} catch (e) {
  console.error('bundle failed to evaluate:', e.message);
  process.exit(1);
}

if (!w.OpenDtvBml) { console.error('OpenDtvBml was not defined - the host glue did not run'); process.exit(1); }
console.log('bundle evaluated, OpenDtvBml present; starting service', chosen.num);
// Optional 3rd argument: the PH EWBS area code, so the receiver-area contract can be tested here
// (engine 2.6.0 write side). areaCode 0/absent means unset, exactly as a real host passes it.
const areaCode = process.argv[4] === undefined ? 0 : Number(process.argv[4]);
w.OpenDtvBml.start(chosen.num, areaCode);

// --- feed the stream in chunks, the way BmlController does ----------------------------------
const CHUNK = 64 * PACKET;
for (let off = 0; off + CHUNK <= size; off += CHUNK) {
  w.OpenDtvBml.push(Buffer.from(raw.subarray(off, off + CHUNK)).toString('base64'));
  if (state.loaded) break;                       // a page loaded: that is the proof we wanted
}

await new Promise((r) => setTimeout(r, 500));

console.log('');
try {
  const d = w.OpenDtvBml.diagnostics ? w.OpenDtvBml.diagnostics() : null;
  console.log('P1 diagnostics:', d ? JSON.stringify(d).slice(0, 300) : 'absent');
  // The receiver area is the PBCNX extension a PH page reads: report it whole, because the head of the
  // diagnostics JSON is truncated and that is exactly where a wrong region hides.
  if (d && d.receiver) console.log('receiver:', JSON.stringify(d.receiver));
  else if (areaCode) console.log('receiver: absent - the engine answered nothing for area code', areaCode);
} catch (e) { console.log('diagnostics threw:', e.message); }
console.log('RESULT');
console.log(`  page loaded : ${state.loaded ? state.loaded.width + 'x' + state.loaded.height : 'NO'}`);
console.log(`  video rect  : ${state.video ? JSON.stringify(state.video) : 'not reported'}`);
console.log(`  errors      : ${state.errors.length}`);
console.log(`  log lines   : ${logs.length}`);
process.exit(state.loaded ? 0 : 1);
