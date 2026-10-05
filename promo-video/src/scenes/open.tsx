import React from 'react';
import {AbsoluteFill, Img, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {M3, rgba, DISPLAY, FONT, gradText, BRAND, segRadius, R} from '../theme';
import {morphTrack, track} from '../lib/shape';
import {MorphShape, MorphClip} from '../m3/MorphShape';
import {Head, Tonal, StatusDot, Chip} from '../m3/Surface';
import {Glyph, type GlyphName} from '../m3/Glyph';
import {Tilt} from '../stage/Stage';
import {EASE, EASE_IN, EASE_SINE, seg, springySettle, mix} from '../lib/ease';

/* ==================================================================== */
/* S01 · 开场：形变中诞生                                                */
/* ==================================================================== */

const LOGO_TRACK = track(
  [0, 'cookie6'],
  [0.3, 'flower6'],
  [0.58, 'sunny12'],
  [0.8, 'circle'],
  [1, 'squircle'],
);

export const S01Logo: React.FC = () => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const S = Math.round(Math.min(width, height) * 0.44);

  const draw = seg(f, 6, 58, EASE);
  const morphP = seg(f, 26, 146, EASE_SINE);
  const shape = morphTrack(LOGO_TRACK, morphP);
  const spin = mix(seg(f, 0, 210), -0.55, 0.2);
  const ringIn = seg(f, 96, 150, EASE);
  const iconIn = seg(f, 126, 168, EASE);

  const ry = mix(seg(f, 0, 158, EASE), -30, 0);
  const rx = mix(seg(f, 0, 158, EASE), 12, 0);

  const titleP = Math.min(1, springySettle(f, fps, 148, {damping: 21, stiffness: 105}));
  const tagP = seg(f, 166, 198, EASE);
  const chipP = seg(f, 180, 210, EASE);

  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', fontFamily: FONT}}>
      <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 54}}>
        <Tilt perspective={1500} rx={rx} ry={ry} style={{width: S, height: S}}>
          <div style={{position: 'relative', width: S, height: S}}>
            {/* 柔光 + 虚线圆环：稳定的「圆」对比正在形变的形状，读起来是光环，
                而不是「画歪的轮廓」（同形状等比放大时圆角不按比例，看着像失误）。 */}
            <div
              style={{
                position: 'absolute',
                inset: -S * 0.3,
                opacity: ringIn * 0.9,
                borderRadius: '50%',
                background: `radial-gradient(circle at center, ${rgba(BRAND.glow, 0.3)} 0%, transparent 62%)`,
                filter: 'blur(8px)',
              }}
            />
            <div style={{position: 'absolute', inset: -S * 0.1, opacity: ringIn}}>
              <svg width={S * 1.2} height={S * 1.2} viewBox={`0 0 ${S * 1.2} ${S * 1.2}`} style={{display: 'block'}}>
                <circle
                  cx={S * 0.6}
                  cy={S * 0.6}
                  r={S * 0.572}
                  fill="none"
                  stroke={rgba(M3.d.primary, 0.38)}
                  strokeWidth={1.6}
                  strokeDasharray="6 13"
                  strokeLinecap="round"
                  transform={`rotate(${spin * 46} ${S * 0.6} ${S * 0.6})`}
                />
              </svg>
            </div>
            {/* 品牌渐变实心（随形变一起变） */}
            <div style={{position: 'absolute', inset: 0, opacity: 1 - iconIn}}>
              <MorphShape
                shape={shape}
                size={S}
                id="s1-fill"
                gradient={{from: '#AFA9FF', via: '#7C78E6', to: '#49B7D6', angle: 125}}
                glow={rgba(BRAND.glow, 0.5)}
                rot={spin}
              />
            </div>
            {/* 描边「画」出来 */}
            <div style={{position: 'absolute', inset: 0}}>
              <MorphShape
                shape={shape}
                size={S}
                id="s1-line"
                stroke={M3.d.primary}
                strokeWidth={3.5}
                draw={draw}
                rot={spin}
              />
            </div>
            {/* 真图标（被当前形状裁切）。放大 12% 让图标自带的圆角落到裁切区之外，
                否则方形裁切下会露出「圆角里的圆角」。 */}
            <MorphClip shape={shape} size={S} rot={spin} style={{position: 'absolute', inset: 0, opacity: iconIn}}>
              <Img
                src={staticFile('cpplayer-icon.png')}
                style={{
                  position: 'absolute',
                  left: '50%',
                  top: '50%',
                  width: '112%',
                  height: '112%',
                  translate: '-50% -50%',
                  objectFit: 'cover',
                }}
              />
            </MorphClip>
          </div>
        </Tilt>

        <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
          <div
            style={{
              fontFamily: DISPLAY,
              fontSize: 122,
              fontWeight: 800,
              letterSpacing: -3.5,
              lineHeight: 1,
              opacity: titleP,
              transform: `translateY(${(1 - titleP) * 40}px)`,
              ...gradText,
            }}
          >
            CPPlayer
          </div>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 35,
              color: M3.d.onSurfaceVariant,
              marginTop: 20,
              letterSpacing: 7,
              opacity: tagP,
              transform: `translateY(${(1 - tagP) * 22}px)`,
            }}
          >
            一套内核 · 无限音源
          </div>
          <div
            style={{
              display: 'flex',
              gap: 14,
              marginTop: 32,
              opacity: chipP,
              transform: `translateY(${(1 - chipP) * 18}px)`,
            }}
          >
            <Chip variant="tonal" tone="primary" size={22} icon={<Glyph name="cube" size={20} />}>
              Kotlin Multiplatform
            </Chip>
            <Chip variant="outlined" tone="neutral" size={22} icon={<Glyph name="globe" size={20} />}>
              Android · Windows · macOS · Linux
            </Chip>
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S02 · 钩子：音源不该绑架播放器                                        */
/* ==================================================================== */

const ROWS = [
  {app: '音乐播放器 A', src: '只认音源 X'},
  {app: '音乐播放器 B', src: '只认音源 Y'},
  {app: '音乐播放器 C', src: '只认音源 Z'},
];

const TEASERS: Array<{icon: GlyphName; label: string}> = [
  {icon: 'swap', label: '热插拔切换'},
  {icon: 'refresh', label: '多音源容灾'},
  {icon: 'layers', label: '缓存按源隔离'},
];

export const S02Hook: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const W = 1140;
  const ROW_H = 106;
  const GAP = 5;

  const qIn = seg(f, 4, 34, EASE);
  const qOut = seg(f, 106, 132, EASE_IN);
  const aIn = seg(f, 134, 166, EASE);

  const rowsIn = ROWS.map((_, i) => springySettle(f, fps, 16 + i * 9));
  const strike = ROWS.map((_, i) => seg(f, 76 + i * 11, 102 + i * 11, EASE));
  const collapse = seg(f, 118, 154, EASE);
  const heroIn = seg(f, 146, 178, EASE);
  const teaserP = seg(f, 158, 182, EASE);

  // 2.5D：整块列表从倾斜归正
  const tiltP = seg(f, 0, 60, EASE);
  const ry = mix(tiltP, -16, 0);
  const rx = mix(tiltP, 9, 0);

  const centerY = ROW_H + GAP;

  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', fontFamily: FONT}}>
      <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
        {/* 标题位：两条文案叠放，避免布局跳动 */}
        <div style={{position: 'relative', height: 190, width: 1320, marginBottom: 48}}>
          <div
            style={{
              position: 'absolute',
              inset: 0,
              opacity: qIn * (1 - qOut),
              transform: `translateY(${(1 - qIn) * 34 - qOut * 30}px)`,
            }}
          >
            <Head size={80} style={{textAlign: 'center', width: '100%'}}>
              换个音源，<span style={{color: M3.d.error}}>就得换个播放器？</span>
            </Head>
          </div>
          <div
            style={{
              position: 'absolute',
              inset: 0,
              opacity: aIn,
              transform: `translateY(${(1 - aIn) * 34}px)`,
            }}
          >
            <Head size={80} style={{textAlign: 'center', width: '100%'}}>
              在 CPPlayer，音源只是一块<span style={{color: M3.d.primary}}>插件</span>
            </Head>
          </div>
        </div>

        <Tilt perspective={1900} rx={rx} ry={ry} style={{width: W, height: ROW_H * 3 + GAP * 2}}>
          <div style={{position: 'relative', width: W, height: ROW_H * 3 + GAP * 2}}>
            {ROWS.map((r, i) => {
              const p = Math.min(1, rowsIn[i]);
              const s = strike[i];
              const y0 = i * (ROW_H + GAP);
              const y = y0 * (1 - collapse) + centerY * collapse;
              return (
                <div
                  key={r.app}
                  style={{
                    position: 'absolute',
                    left: 0,
                    top: y,
                    width: W,
                    height: ROW_H,
                    opacity: p * (1 - collapse),
                    borderRadius: segRadius(i, 3),
                    background: M3.d.surfaceHigh,
                    display: 'flex',
                    alignItems: 'center',
                    padding: '0 26px',
                    gap: 18,
                    boxSizing: 'border-box',
                    overflow: 'hidden',
                  }}
                >
                  <Tonal size={60} tone="surface">
                    <Glyph name="note" size={31} color={M3.d.onSurfaceVariant} />
                  </Tonal>
                  <div style={{flex: 1, minWidth: 0}}>
                    <div style={{fontSize: 32, fontWeight: 600, color: M3.d.onSurface}}>{r.app}</div>
                    <div style={{fontSize: 22, color: M3.d.onSurfaceVariant, marginTop: 4}}>{r.src}</div>
                  </div>
                  <div style={{color: M3.d.error, opacity: 1 - s, display: 'flex', alignItems: 'center', gap: 10}}>
                    <Glyph name="lock" size={30} />
                  </div>
                  {/* 划掉 */}
                  <div
                    style={{
                      position: 'absolute',
                      left: 24,
                      right: 78,
                      top: '50%',
                      height: 2.5,
                      borderRadius: 999,
                      background: M3.d.error,
                      transform: `scaleX(${s})`,
                      transformOrigin: 'left center',
                    }}
                  />
                  <div
                    style={{
                      position: 'absolute',
                      right: 26,
                      top: '50%',
                      translate: '0 -50%',
                      opacity: s,
                      color: M3.d.error,
                    }}
                  >
                    <Glyph name="x" size={28} strokeWidth={2.6} />
                  </div>
                </div>
              );
            })}

            {/* 合并后的一行 */}
            <div
              style={{
                position: 'absolute',
                left: 0,
                top: centerY,
                width: W,
                height: ROW_H,
                opacity: heroIn,
                transform: `scale(${0.88 + 0.12 * heroIn})`,
                borderRadius: R.xl,
                background: rgba(M3.d.primaryContainer, 0.5),
                border: `1.5px solid ${rgba(M3.d.primary, 0.5)}`,
                display: 'flex',
                alignItems: 'center',
                padding: '0 26px',
                gap: 18,
                boxSizing: 'border-box',
              }}
            >
              <Tonal size={60} tone="primary">
                <Glyph name="puzzle" size={31} />
              </Tonal>
              <div style={{flex: 1, minWidth: 0}}>
                <div style={{fontSize: 33, fontWeight: 700, color: M3.d.primary}}>CPPlayer</div>
                <div style={{fontSize: 22, color: M3.d.onSurfaceVariant, marginTop: 4}}>
                  本体只做播放 · 音源即插即用
                </div>
              </div>
              <StatusDot tone="ok" size={14} />
              <span style={{fontSize: 23, color: M3.d.secondary, fontWeight: 600}}>自由插拔</span>
            </div>
          </div>
        </Tilt>

        <div
          style={{
            display: 'flex',
            gap: 12,
            marginTop: 36,
            opacity: teaserP,
            transform: `translateY(${(1 - teaserP) * 16}px)`,
          }}
        >
          {TEASERS.map((t) => (
            <Chip key={t.label} variant="outlined" tone="neutral" size={21} icon={<Glyph name={t.icon} size={19} />}>
              {t.label}
            </Chip>
          ))}
        </div>
      </div>
    </AbsoluteFill>
  );
};
