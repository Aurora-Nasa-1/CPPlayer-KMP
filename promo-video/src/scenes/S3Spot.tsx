import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, FONT, MONO} from '../theme';
import {Stage} from '../components/Stage';
import {UiCardView} from '../components/UiCard';
import {DUR} from '../timeline';
import {LANDSCAPES, PORTRAITS} from '../cards';

const CALLOUTS = [
  {t: '封面推满整屏', d: '播放页不留边，只留音乐', c: C.primary, bg: C.primaryContainer},
  {t: '歌词逐字推进', d: '当前字亮起，不开字幕也能跟唱', c: C.secondary, bg: C.secondaryContainer},
  {t: '封面取色', d: '主题跟着专辑封面一起变', c: C.tertiary, bg: C.tertiaryContainer},
];

const Callout: React.FC<{i: number; frame: number; fps: number}> = ({i, frame, fps}) => {
  const item = CALLOUTS[i];
  const p = spring({frame: frame - 70 - i * 16, fps, config: {damping: 200, mass: 0.7, stiffness: 95}});
  const lineW = interpolate(p, [0, 1], [0, 96]);

  return (
    <div style={{display: 'flex', alignItems: 'flex-start', gap: 0, opacity: p}}>
      <div style={{width: 96, height: 60, display: 'flex', alignItems: 'center'}}>
        <div
          style={{
            width: lineW,
            height: 1.5,
            background: item.c,
            opacity: 0.5,
            marginLeft: 96 - lineW,
          }}
        />
        <div
          style={{
            width: 9,
            height: 9,
            borderRadius: '50%',
            background: item.c,
            marginLeft: -4,
          }}
        />
      </div>
      <div style={{transform: `translateX(${(1 - p) * -10}px)`}}>
        <div style={{fontSize: 31, fontWeight: 700, color: C.ink, letterSpacing: -0.3}}>{item.t}</div>
        <div style={{fontSize: 21, color: C.inkVar, marginTop: 5}}>{item.d}</div>
      </div>
    </div>
  );
};

export const S3Spot: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  const portrait = PORTRAITS[0];
  const landscape = LANDSCAPES[0];

  // A 页：手机播放页特写 + 三条批注；B 页：桌面双栏 + 收窄演示
  const aOut = interpolate(frame, [186, 206], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const bIn = interpolate(frame, [196, 218], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const enter = spring({frame: frame - 4, fps, config: {damping: 200, mass: 0.8, stiffness: 85}});

  // 收窄：800 → 540，高度不变，右侧栏被裁掉
  const winW = interpolate(frame, [330, 392], [800, 540], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const narrow = frame > 392;

  return (
    <AbsoluteFill>
      <Stage index="03" label="播放与桌面" duration={DUR.spot} vo={2}>
        {/* A：手机播放页 */}
        <AbsoluteFill style={{opacity: aOut}}>
          <div
            style={{
              position: 'absolute',
              left: 470,
              top: 395,
              opacity: Math.min(1, enter * 1.5),
              transform: `translate(-50%, -50%) translateY(${(1 - enter) * 40}px) scale(${
                0.88 + 0.12 * enter
              })`,
            }}
          >
            <UiCardView card={portrait} width={340} />
          </div>

          <div
            style={{
              position: 'absolute',
              left: 720,
              top: 196,
              display: 'flex',
              flexDirection: 'column',
              gap: 46,
            }}
          >
            {CALLOUTS.map((_, i) => (
              <Callout key={i} i={i} frame={frame} fps={fps} />
            ))}
          </div>
        </AbsoluteFill>

        {/* B：桌面双栏 */}
        <AbsoluteFill style={{opacity: bIn}}>
          <div
            style={{
              position: 'absolute',
              left: 840,
              top: 395,
              transform: 'translate(-50%, -50%)',
            }}
          >
            <div style={{position: 'relative'}}>
              <UiCardView card={landscape} width={winW} fixedHeight={520} />

              {/* 双栏标注 */}
              <div
                style={{
                  position: 'absolute',
                  left: 10,
                  top: -34,
                  width: `${(Math.min(winW, 800) / 2 - 12) * bIn}px`,
                  height: 16,
                  borderTop: `2px solid ${C.primary}`,
                  borderLeft: `2px solid ${C.primary}`,
                  borderRight: `2px solid ${C.primary}`,
                  borderTopLeftRadius: 6,
                  borderTopRightRadius: 6,
                  opacity: bIn,
                }}
              />
              <div
                style={{
                  position: 'absolute',
                  right: 10,
                  top: -34,
                  width: `${Math.max(0, (winW / 2 - 12) * bIn)}px`,
                  height: 16,
                  borderTop: `2px solid ${C.secondary}`,
                  borderLeft: `2px solid ${C.secondary}`,
                  borderRight: `2px solid ${C.secondary}`,
                  borderTopLeftRadius: 6,
                  borderTopRightRadius: 6,
                  opacity: bIn * (narrow ? 0.25 : 1),
                }}
              />
              <div
                style={{
                  position: 'absolute',
                  left: 10,
                  top: -32,
                  fontSize: 20,
                  fontWeight: 700,
                  color: C.primary,
                  opacity: bIn,
                  transform: 'translateY(-100%)',
                  fontFamily: MONO,
                }}
              >
                列表
              </div>
              <div
                style={{
                  position: 'absolute',
                  right: 10,
                  top: -32,
                  fontSize: 20,
                  fontWeight: 700,
                  color: C.secondary,
                  opacity: bIn * (narrow ? 0.25 : 1),
                  transform: 'translateY(-100%)',
                  fontFamily: MONO,
                }}
              >
                详情
              </div>

            </div>
          </div>
        </AbsoluteFill>
      </Stage>
    </AbsoluteFill>
  );
};
