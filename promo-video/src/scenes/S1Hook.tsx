import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, MONO} from '../theme';
import {CardBack} from '../components/UiCard';
import {Subtitle} from '../components/Subtitle';
import {Reveal, RevealChars} from '../components/Ui';
import {SceneFade} from '../components/Stage';
import {DUR} from '../timeline';
import {VO} from '../vo.generated';

const CARD_W = 220;

export const S1Hook: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  // 三张牌先叠着进场，随后微微摊开 —— 暗示「还有很多张」
  const spread = interpolate(frame, [86, 132], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <SceneFade duration={DUR.hook} fadeIn={false}>
      <AbsoluteFill style={{alignItems: 'center'}}>
        <div style={{marginTop: 140}}>
          <RevealChars
            text="CPPlayer"
            delay={14}
            stagger={2.6}
            style={{fontSize: 96, fontWeight: 800, letterSpacing: -3, color: C.ink}}
          />
        </div>

        <Reveal delay={34} y={16} style={{marginTop: 20}}>
          <div
            style={{
              fontFamily: MONO,
              fontSize: 21,
              letterSpacing: 5,
              color: C.outlineStrong,
            }}
          >
            ANDROID · WINDOWS · MACOS · LINUX
          </div>
        </Reveal>

        <div style={{position: 'relative', width: '100%', height: 566, marginTop: 44}}>
          {[0, 1, 2].map((i) => {
            const center = 1;
            const d = i - center;
            const p = spring({
              frame: frame - 30 - i * 7,
              fps,
              config: {damping: 16, mass: 0.9, stiffness: 78},
            });
            const x = d * (190 + spread * 64);
            const y = (1 - p) * 90 + Math.abs(d) * spread * 12;
            const rot = d * (6 + spread * 5) + (1 - p) * d * 6;
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  left: '50%',
                  top: '50%',
                  zIndex: 5 - Math.abs(d),
                  opacity: p,
                  transform: `translate(-50%, -50%) translate(${x}px, ${y}px) rotate(${rot}deg) scale(${
                    0.86 + 0.14 * p
                  })`,
                }}
              >
                <CardBack width={CARD_W} />
              </div>
            );
          })}
        </div>
      </AbsoluteFill>

      <Subtitle lines={VO[0].lines} seconds={VO[0].seconds} voStart={VO[0].voStart} />
    </SceneFade>
  );
};
