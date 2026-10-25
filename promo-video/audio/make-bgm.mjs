// 生成 60s 背景音乐（纯合成，无可疑版权素材）。
// 100 BPM ⇒ 1 小节 2.4s、全片正好 25 小节；第 22 小节（52.8s）正好落在收尾镜头。
// 用法：node audio/make-bgm.mjs  → public/bgm.wav
import fs from 'node:fs';
import path from 'node:path';

const SR = 44100;
const DURATION = 60;
const N = SR * DURATION;
const BPM = 100;
const BEAT = 60 / BPM; // 0.6s
const BAR = BEAT * 4; // 2.4s
const BARS = 25;

const L = new Float32Array(N);
const R = new Float32Array(N);
const WET_L = new Float32Array(N);
const WET_R = new Float32Array(N);

const midi = (n) => 440 * Math.pow(2, (n - 69) / 12);
const TAU = Math.PI * 2;

// 可复现的伪随机
let seed = 20261025;
const rnd = () => {
  seed |= 0;
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const noise = () => rnd() * 2 - 1;

const add = (buf, i, v) => {
  if (i >= 0 && i < N) buf[i] += v;
};

// A 小调：Am9 – Fmaj7 – Cmaj7 – G6
const CHORDS = [
  {pad: [57, 60, 64, 67, 71], bass: 45}, // Am9
  {pad: [53, 57, 60, 64, 67], bass: 41}, // Fmaj7
  {pad: [60, 64, 67, 71, 74], bass: 48}, // Cmaj7
  {pad: [55, 59, 62, 64, 67], bass: 43}, // G6
];

function pad(freq, t0, dur, amp, pan) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor((dur + 1.4) * SR);
  const atk = 0.55;
  const rel = 1.2;
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    let env = 1;
    if (t < atk) env = t / atk;
    else if (t > dur) env = Math.max(0, 1 - (t - dur) / rel);
    if (env <= 0) continue;
    // 缓慢起伏，避免长音发死
    env *= 0.82 + 0.18 * Math.sin(TAU * 0.17 * (t0 + t));
    const ph = TAU * freq * t;
    let v =
      Math.sin(ph) * 1.0 +
      Math.sin(ph * 2 + 0.2) * 0.42 +
      Math.sin(ph * 3 + 0.5) * 0.16 +
      Math.sin(ph * 4 + 0.9) * 0.06;
    v *= 0.055 * amp * env;
    const l = Math.cos((pan + 1) * Math.PI / 4);
    const r = Math.sin((pan + 1) * Math.PI / 4);
    add(L, start + k, v * l);
    add(R, start + k, v * r);
    add(WET_L, start + k, v * l * 0.5);
    add(WET_R, start + k, v * r * 0.5);
  }
}

function bassNote(freq, t0, dur, amp) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor((dur + 0.12) * SR);
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    const atk = 0.012;
    const env =
      t < atk ? t / atk : Math.exp(-(t - atk) * 2.6) * 0.9 + 0.1 * Math.exp(-(t - atk) * 0.4);
    const ph = TAU * freq * t;
    const v = (Math.sin(ph) + 0.18 * Math.sin(ph * 2)) * 0.16 * amp * env;
    add(L, start + k, v);
    add(R, start + k, v);
  }
}

function pluck(freq, t0, amp, pan = 0) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor(0.55 * SR);
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    const env = (t < 0.004 ? t / 0.004 : 1) * Math.exp(-t * 11);
    const ph = TAU * freq * t;
    const v =
      (Math.sin(ph) + 0.28 * Math.sin(ph * 2) + 0.1 * Math.sin(ph * 3)) * 0.05 * amp * env;
    const l = Math.cos((pan + 1) * Math.PI / 4);
    const r = Math.sin((pan + 1) * Math.PI / 4);
    add(L, start + k, v * l);
    add(R, start + k, v * r);
    add(WET_L, start + k, v * l * 1.5);
    add(WET_R, start + k, v * r * 1.5);
  }
}

function kick(t0, amp) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor(0.3 * SR);
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    const f = 46 + 78 * Math.exp(-t * 34);
    const env = Math.exp(-t * 9) * (t < 0.002 ? t / 0.002 : 1);
    const v = Math.sin(TAU * f * t) * 0.34 * amp * env;
    add(L, start + k, v);
    add(R, start + k, v);
  }
}

let hpState = 0;
function hat(t0, amp) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor(0.06 * SR);
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    const n = noise();
    const hp = n - hpState;
    hpState = n;
    const env = Math.exp(-t * 70);
    const v = hp * 0.055 * amp * env;
    add(L, start + k, v * 0.9);
    add(R, start + k, v * 1.1);
  }
}

function clap(t0, amp) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor(0.16 * SR);
  let prev = 0;
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    const n = noise();
    const bp = n - prev * 0.72;
    prev = n;
    const env = Math.exp(-t * 26) * (1 - Math.exp(-t * 400));
    const v = bp * 0.07 * amp * env;
    add(L, start + k, v);
    add(R, start + k, v);
    add(WET_L, start + k, v * 0.8);
    add(WET_R, start + k, v * 0.8);
  }
}

function swell(t0, dur, amp) {
  const start = Math.floor(t0 * SR);
  const len = Math.floor(dur * SR);
  let prev = 0;
  for (let k = 0; k < len; k++) {
    const t = k / SR;
    if (start + k >= N) break;
    const n = noise();
    const hp = n - prev;
    prev = n;
    const x = t / dur;
    const env = Math.pow(x, 2.2) * (1 - x * 0.35);
    const v = hp * 0.03 * amp * env;
    add(L, start + k, v);
    add(R, start + k, v * 0.86);
  }
}

// —— 编排 ——
for (let bar = 0; bar < BARS; bar++) {
  const t0 = bar * BAR;
  const ch = CHORDS[bar % 4];
  const section = bar < 2 ? 'intro' : bar < 6 ? 'soft' : bar < 14 ? 'build' : bar < 22 ? 'full' : 'outro';

  // pad
  const padAmp = section === 'intro' ? 0.8 : section === 'outro' ? 0.7 : 1;
  ch.pad.forEach((n, i) => {
    pad(midi(n), t0, BAR * 0.98, padAmp, i % 2 === 0 ? -0.5 : 0.5);
    if (section === 'full' && i < 3) pad(midi(n + 12), t0, BAR * 0.9, padAmp * 0.42, i % 2 ? 0.6 : -0.6);
  });

  // bass
  if (section !== 'intro') {
    if (section === 'full') {
      for (let e = 0; e < 8; e++) bassNote(midi(ch.bass), t0 + e * BEAT * 0.5, BEAT * 0.42, e % 2 === 0 ? 1 : 0.62);
    } else {
      bassNote(midi(ch.bass), t0, BEAT * 1.6, 1);
      bassNote(midi(ch.bass), t0 + BEAT * 2, BEAT * 1.6, 0.85);
    }
    if (section === 'outro') bassNote(midi(ch.bass), t0, BEAT * 3, 0.7);
  }

  // drums
  if (section === 'soft' || section === 'build' || section === 'full') {
    kick(t0, 0.9);
    kick(t0 + BEAT * 2, 0.75);
    if (section === 'full') kick(t0 + BEAT * 3.5, 0.45);
  }
  if (section === 'build' || section === 'full') {
    for (let e = 0; e < 8; e++) hat(t0 + e * BEAT * 0.5, e % 2 === 0 ? 1 : 0.55);
  }
  if (section === 'full' && bar % 2 === 1) clap(t0 + BEAT * 2, 1);

  // 琶音
  if (section === 'build' || section === 'full' || section === 'outro') {
    const tones = ch.pad;
    const pattern = [0, 2, 1, 3, 2, 4, 3, 1];
    const amp = section === 'outro' ? 0.55 : 1;
    pattern.forEach((idx, e) => {
      const n = tones[idx % tones.length] + (e === 5 ? 12 : 0);
      pluck(midi(n + 12), t0 + e * BEAT * 0.5, amp, e % 2 === 0 ? -0.3 : 0.3);
    });
  }

  // 段落推进的噪声上扬
  if (bar === 5 || bar === 13 || bar === 21) swell(t0 + BAR * 0.45, BAR * 0.55, bar === 21 ? 0.6 : 1);
}

// —— 简易延迟（给湿声一点空间感）——
const delaySamples = Math.floor(BEAT * 0.75 * SR);
for (let i = delaySamples; i < N; i++) {
  WET_L[i] += WET_R[i - delaySamples] * 0.34;
  WET_R[i] += WET_L[i - delaySamples] * 0.34;
}

// —— 汇总 + 软化 + 归一 ——
let peak = 0;
for (let i = 0; i < N; i++) {
  const l = Math.tanh((L[i] + WET_L[i] * 0.5) * 1.15);
  const r = Math.tanh((R[i] + WET_R[i] * 0.5) * 1.15);
  L[i] = l;
  R[i] = r;
  peak = Math.max(peak, Math.abs(l), Math.abs(r));
}

const gain = 0.82 / (peak || 1);
const fadeIn = 0.9 * SR;
const fadeOutStart = N - 2.6 * SR;
const pcm = Buffer.alloc(N * 4);
for (let i = 0; i < N; i++) {
  let env = 1;
  if (i < fadeIn) env = i / fadeIn;
  if (i > fadeOutStart) env *= Math.max(0, (N - i) / (N - fadeOutStart));
  const l = Math.max(-1, Math.min(1, L[i] * gain * env));
  const r = Math.max(-1, Math.min(1, R[i] * gain * env));
  pcm.writeInt16LE(Math.round(l * 32767), i * 4);
  pcm.writeInt16LE(Math.round(r * 32767), i * 4 + 2);
}

const header = Buffer.alloc(44);
header.write('RIFF', 0);
header.writeUInt32LE(36 + pcm.length, 4);
header.write('WAVE', 8);
header.write('fmt ', 12);
header.writeUInt32LE(16, 16);
header.writeUInt16LE(1, 20);
header.writeUInt16LE(2, 22);
header.writeUInt32LE(SR, 24);
header.writeUInt32LE(SR * 4, 28);
header.writeUInt16LE(4, 32);
header.writeUInt16LE(16, 34);
header.write('data', 36);
header.writeUInt32LE(pcm.length, 40);

const outDir = path.resolve('public');
fs.mkdirSync(outDir, {recursive: true});
const out = path.join(outDir, 'bgm.wav');
fs.writeFileSync(out, Buffer.concat([header, pcm]));
console.log(`written ${out} (${(pcm.length / 1024 / 1024).toFixed(1)} MB, peak gain ${gain.toFixed(2)})`);
