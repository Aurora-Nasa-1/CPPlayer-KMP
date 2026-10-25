import React from 'react';
import {useCurrentFrame, useVideoConfig} from 'remotion';
import {C, FONT} from '../theme';

// 常驻字幕：把整段口播按字数比例切到每一句，跟着配音走。
// 语速由 TTS 决定，所以这里只做「相对切分」，时间基准仍是真实音频时长。
export const Subtitle: React.FC<{
  lines: readonly string[];
  seconds: number;
  voStart: number;
  bottom?: number;
}> = ({lines, seconds, voStart, bottom = 52}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const t = (frame - voStart) / fps;

  if (t < -0.15 || lines.length === 0) return null;

  const total = lines.join('').length;
  let acc = 0;
  const windows = lines.map((l) => {
    const s = (acc / total) * seconds;
    acc += l.length;
    return [s, (acc / total) * seconds] as const;
  });

  let idx = windows.findIndex(([s, e]) => t >= s && t < e);
  if (idx < 0) idx = t >= windows[windows.length - 1][1] ? lines.length - 1 : 0;

  const [s, e] = windows[idx];
  const inP = Math.min(1, Math.max(0, (t - s) / 0.16 + (idx === 0 && t < s ? 1 : 0)));
  const outP = Math.min(1, Math.max(0, (e - t) / 0.22));
  const opacity = Math.min(inP, outP, t < s ? Math.max(0, 1 - (s - t) / 0.4) : 1);

  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom,
        display: 'flex',
        justifyContent: 'center',
        pointerEvents: 'none',
      }}
    >
      <div
        style={{
          maxWidth: 1560,
          padding: '13px 34px',
          borderRadius: 18,
          background: 'rgba(252,248,252,0.82)',
          border: `1px solid ${C.outline}`,
          boxShadow: '0 8px 26px rgba(28,27,32,0.10)',
          fontFamily: FONT,
          fontSize: 33,
          lineHeight: 1.45,
          fontWeight: 600,
          color: C.ink,
          letterSpacing: 0.6,
          textAlign: 'center',
          opacity,
          transform: `translateY(${(1 - Math.min(1, opacity)) * 8}px)`,
        }}
      >
        {lines[idx]}
      </div>
    </div>
  );
};
