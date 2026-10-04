import React from 'react';
import {useCurrentFrame} from 'remotion';

/** 终端逐字打字机（打完光标继续闪烁） */
export const Typewriter: React.FC<{
  readonly text: string;
  readonly start: number;
  /** 每帧字符数 */
  readonly cpf?: number;
  readonly style?: React.CSSProperties;
}> = ({text, start, cpf = 1.1, style}) => {
  const frame = useCurrentFrame();
  const n = Math.max(0, Math.min(text.length, Math.floor((frame - start) * cpf)));
  const typing = n < text.length;
  const cursorOn = typing || Math.floor(frame / 9) % 2 === 0;
  return (
    <div style={{whiteSpace: 'pre', ...style}}>
      {text.slice(0, n)}
      {frame >= start && cursorOn ? (
        <span style={{color: '#7DE3A0'}}>▌</span>
      ) : null}
    </div>
  );
};
