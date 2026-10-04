import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C, FONT} from '../theme';
import {enter} from '../lib/anim';
import {Backdrop} from './Backdrop';
import {Chip} from './Bits';
import {LogoMark} from './LogoMark';

/** 收尾卡：logo + 产品名 + 标语 + 平台 chips */
export const EndCard: React.FC<{
  readonly tagline: string;
  readonly note?: string;
  readonly chips?: readonly string[];
  readonly compact?: boolean;
  readonly start?: number;
}> = ({tagline, note, chips = [], compact = false, start = 0}) => {
  const frame = useCurrentFrame();
  const p1 = enter(frame, start);
  const p2 = enter(frame, start + 12);
  const p3 = enter(frame, start + 24);
  const logo = compact ? 170 : 220;
  const name = compact ? 88 : 118;
  const tag = compact ? 46 : 56;
  return (
    <AbsoluteFill>
      <Backdrop grid={false} />
      <AbsoluteFill
        style={{
          justifyContent: 'center',
          alignItems: 'center',
          flexDirection: 'column',
          gap: compact ? 30 : 40,
        }}
      >
        <div style={{opacity: p1, scale: `${0.88 + p1 * 0.12}`}}>
          <LogoMark size={logo} />
        </div>
        <div
          style={{
            fontFamily: FONT,
            fontSize: name,
            fontWeight: 800,
            color: C.text,
            letterSpacing: '0.02em',
            opacity: p1,
            translate: `0px ${(1 - p1) * 26}px`,
          }}
        >
          CPPlayer
        </div>
        <div
          style={{
            fontFamily: FONT,
            fontSize: tag,
            fontWeight: 600,
            color: C.text,
            opacity: p2,
            translate: `0px ${(1 - p2) * 22}px`,
          }}
        >
          {tagline}
        </div>
        {note ? (
          <div
            style={{
              fontFamily: FONT,
              fontSize: compact ? 30 : 34,
              color: C.sub,
              opacity: p2,
            }}
          >
            {note}
          </div>
        ) : null}
        {chips.length > 0 ? (
          <div
            style={{
              display: 'flex',
              gap: 22,
              marginTop: compact ? 16 : 26,
              opacity: p3,
              translate: `0px ${(1 - p3) * 18}px`,
            }}
          >
            {chips.map((c) => (
              <Chip key={c} size={compact ? 30 : 34}>
                {c}
              </Chip>
            ))}
          </div>
        ) : null}
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
