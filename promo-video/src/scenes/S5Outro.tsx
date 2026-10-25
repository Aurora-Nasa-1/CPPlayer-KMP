import React from 'react';
import {AbsoluteFill, interpolate, useCurrentFrame} from 'remotion';
import {C, MONO} from '../theme';
import {LogoMark, PulseRings, Wordmark} from '../components/Brand';
import {Reveal} from '../components/Ui';
import {Subtitle} from '../components/Subtitle';
import {SceneFade} from '../components/Stage';
import {DUR} from '../timeline';
import {VO} from '../vo.generated';

export const S5Outro: React.FC = () => {
  const frame = useCurrentFrame();

  return (
    <SceneFade duration={DUR.outro}>
      <AbsoluteFill
        style={{
          backgroundImage: `radial-gradient(1000px 760px at 50% 26%, ${C.secondaryContainer} 0%, rgba(252,248,252,0) 64%)`,
          opacity: 0.8,
        }}
      />
      <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', flexDirection: 'column'}}>
        <div style={{position: 'relative', display: 'flex', justifyContent: 'center'}}>
          <PulseRings size={260} count={2} period={80} color={C.secondary} />
          <LogoMark size={132} delay={2} />
        </div>

        <Reveal delay={10} y={26} style={{marginTop: 46}}>
          <Wordmark size={100} />
        </Reveal>

        <Reveal delay={20} y={18} style={{marginTop: 18}}>
          <div style={{fontSize: 34, color: C.inkVar, letterSpacing: 4, fontWeight: 500}}>
            跨平台 · 插件化 · 你的界面
          </div>
        </Reveal>

        <Reveal delay={30} y={16} style={{marginTop: 40}}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 14,
              padding: '14px 28px',
              borderRadius: 999,
              background: C.ink,
              boxShadow: '0 12px 34px rgba(28,27,32,0.20)',
            }}
          >
            <svg width={24} height={24} viewBox="0 0 16 16" fill={C.white}>
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8 8 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
            </svg>
            <div style={{fontFamily: MONO, fontSize: 23, color: C.white, letterSpacing: 0.8}}>
              Aurora-Nasa-1 / CPPlayer-KMP
            </div>
          </div>
        </Reveal>

        <Reveal delay={42} y={12} style={{marginTop: 22}}>
          <div style={{fontSize: 22, color: C.outlineStrong, letterSpacing: 2}}>
            开源 · Apache License 2.0 · Android / Windows / macOS / Linux
          </div>
        </Reveal>

        <div
          style={{
            marginTop: 28,
            height: 3,
            width: interpolate(frame, [50, 90], [0, 400], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
            borderRadius: 2,
            background: `linear-gradient(90deg, ${C.primary}, ${C.secondary})`,
            opacity: 0.7,
          }}
        />
      </AbsoluteFill>

      <Subtitle lines={VO[4].lines} seconds={VO[4].seconds} voStart={VO[4].voStart} />
    </SceneFade>
  );
};
