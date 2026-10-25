// 生成口播：逐段调用 edge-tts，量出真实时长，再写出一份「时间轴」给 Remotion 用。
// 用法：node scripts/build-vo.mjs
// 产物：public/vo/<id>.mp3、src/vo.generated.ts（含每段时长与整片时间轴）
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const ROOT = path.resolve('.');
const SCRIPT = JSON.parse(fs.readFileSync(path.join(ROOT, 'audio/vo-script.json'), 'utf8'));
const PY = path.join(ROOT, '.venv-tts/Scripts/python.exe');
const FPS = 30;
const OUT_DIR = path.join(ROOT, 'public/vo');
const LEAD = 14; // 每段配音相对场景起点延后 14 帧开口，给画面留出「起势」
const TAIL = 34; // 每段说完之后再留 34 帧（约 1.1s）换气

fs.mkdirSync(OUT_DIR, {recursive: true});

const durationOf = (file) => {
  const out = execFileSync(
    'ffprobe',
    ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file],
    {encoding: 'utf8'},
  );
  return parseFloat(out.trim());
};

const segments = [];

for (const seg of SCRIPT.segments) {
  const text = seg.lines.join('');
  const file = path.join(OUT_DIR, `${seg.id}.mp3`);
  // --rate 传的是「相对语速」，统一由 vo-script.json 控制
  execFileSync(PY, [
    '-m',
    'edge_tts',
    '--voice',
    SCRIPT.voice,
    // 注意必须用 --rate=... 形式：`--rate -2%` 会被 argparse 当成新选项
    `--rate=${SCRIPT.rate}`,
    '--text',
    text,
    '--write-media',
    file,
  ]);
  // 统一响度：TTS 原始电平偏低（整片均值只有 -23 dB），先归一到 -16 LUFS 再量时长
  const raw = file.replace(/\.mp3$/, '.raw.mp3');
  execFileSync('ffmpeg', [
    '-y',
    '-v',
    'error',
    '-i',
    file,
    '-af',
    'loudnorm=I=-16:TP=-1.5:LRA=11',
    '-ar',
    '44100',
    '-b:a',
    '192k',
    raw,
  ]);
  fs.renameSync(raw, file);

  const seconds = durationOf(file);
  segments.push({
    id: seg.id,
    file: `vo/${seg.id}.mp3`,
    lines: seg.lines,
    seconds,
    chars: text.length,
  });
  console.log(`${seg.id.padEnd(9)} ${seconds.toFixed(2)}s  ${text.length} 字`);
}

// 场景时长 = 开口延后 + 配音 + 换气；整片 = 各场景之和
let cursor = 0;
const timeline = segments.map((s) => {
  const dur = Math.ceil(LEAD + s.seconds * FPS + TAIL);
  const entry = {...s, start: cursor, dur, voStart: LEAD};
  cursor += dur;
  return entry;
});

const total = cursor;
const speech = segments.reduce((a, s) => a + s.seconds, 0);

const ts = `// 由 scripts/build-vo.mjs 生成，请勿手改（改 audio/vo-script.json 后重跑脚本）
export const VO = ${JSON.stringify(timeline, null, 2)} as const;

export const TOTAL = ${total};
export const FPS = ${FPS};
export const SPEECH_SECONDS = ${speech.toFixed(2)};
`;

fs.writeFileSync(path.join(ROOT, 'src/vo.generated.ts'), ts);
console.log(`\n配音合计 ${speech.toFixed(2)}s，整片 ${total} 帧 = ${(total / FPS).toFixed(2)}s`);
