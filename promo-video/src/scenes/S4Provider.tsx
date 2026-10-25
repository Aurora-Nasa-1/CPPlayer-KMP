import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, FONT, MONO, shadow} from '../theme';
import {Stage} from '../components/Stage';
import {Card, Chip, Reveal, RevealChars} from '../components/Ui';
import {DUR} from '../timeline';

const MANIFEST = [
  {t: '{', c: 'rgba(229,225,233,0.55)'},
  {t: '  "id": "my-source",', c: '#E5E1E9'},
  {t: '  "name": "我的音源",', c: '#E5E1E9'},
  {t: '  "type": "http",', c: '#E5E1E9'},
  {t: '  "contract": "standard",', c: '#9CF1EE'},
  {t: '  "entryPoint": "http://127.0.0.1:3000",', c: '#E5E1E9'},
  {t: '  "capabilities": [', c: '#C1C1FF'},
  {t: '    "search", "playback",', c: '#FFDBCF'},
  {t: '    "wordSyncedLyrics", "userPlaylists"', c: '#FFDBCF'},
  {t: '  ],', c: '#C1C1FF'},
  {t: '  "settingsSchema": [ … ]', c: 'rgba(229,225,233,0.6)'},
  {t: '}', c: 'rgba(229,225,233,0.55)'},
];

const CAPS = [
  {n: '搜索', on: true},
  {n: '播放', on: true},
  {n: '歌词', on: true},
  {n: '歌单', on: true},
  {n: '歌手', on: true},
  {n: '专辑', on: true},
  {n: '推荐', on: true},
  {n: '评论', on: false},
  {n: '云盘', on: false},
];

export const S4Provider: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  // 卡包被拖进界面：从右下飞向配置区，缩小淡出
  const drop = interpolate(frame, [300, 372], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const dropX = interpolate(drop, [0, 1], [260, 1180]);
  const dropY = interpolate(drop, [0, 1], [1000, 210]);
  const dropScale = interpolate(drop, [0, 0.7, 1], [1, 0.72, 0.3]);
  const dropOpacity = interpolate(drop, [0, 0.75, 1], [1, 1, 0]);

  const strike = interpolate(frame, [500, 532], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <AbsoluteFill>
      <Stage index="04" label="音源插件" duration={DUR.provider} vo={3} labelColor={C.tertiary}>
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            height: '100%',
            gap: 24,
          }}
        >
          <div>
            <RevealChars
              text="音源不是内置的，是写出来的"
              delay={2}
              stagger={1.6}
              style={{fontSize: 58, fontWeight: 700, letterSpacing: -1.2, color: C.ink}}
            />
            <Reveal delay={12}>
              <div style={{fontSize: 25, color: C.inkVar, marginTop: 10}}>
                一个 manifest.json，两个接口，剩下的界面宿主帮你长出来。
              </div>
            </Reveal>
          </div>

          <div style={{display: 'flex', gap: 40, alignItems: 'flex-start'}}>
            {/* manifest 代码卡 */}
            <Card
              bg={C.ink}
              border={false}
              style={{width: 860, minHeight: 486, padding: '24px 30px 22px'}}
            >
              <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14}}>
                {['#FF8A76', '#FFD37A', '#8CE0C8'].map((c) => (
                  <div key={c} style={{width: 10, height: 10, borderRadius: '50%', background: c}} />
                ))}
                <div
                  style={{
                    marginLeft: 10,
                    fontFamily: MONO,
                    fontSize: 18,
                    letterSpacing: 1,
                    color: 'rgba(229,225,233,0.5)',
                  }}
                >
                  manifest.json
                </div>
              </div>

              <div style={{display: 'flex', flexDirection: 'column', gap: 3}}>
                {MANIFEST.map((l, i) => {
                  const p = interpolate(frame, [22 + i * 9, 34 + i * 9], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  });
                  return (
                    <div
                      key={i}
                      style={{
                        fontFamily: MONO,
                        fontSize: 19,
                        lineHeight: 1.42,
                        whiteSpace: 'pre',
                        color: l.c,
                        opacity: p,
                        transform: `translateX(${(1 - p) * -10}px)`,
                      }}
                    >
                      {l.t}
                    </div>
                  );
                })}
              </div>

              {/* 两个接口 */}
              <Reveal delay={150} y={12} style={{marginTop: 16}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 12}}>
                  <span style={{fontSize: 20, color: 'rgba(229,225,233,0.6)'}}>只要两个</span>
                  <span
                    style={{
                      fontFamily: MONO,
                      fontSize: 19,
                      fontWeight: 700,
                      color: C.onPrimaryContainer,
                      background: '#C1C1FF',
                      borderRadius: 7,
                      padding: '3px 11px',
                    }}
                  >
                    POST /search
                  </span>
                  <span
                    style={{
                      fontFamily: MONO,
                      fontSize: 19,
                      fontWeight: 700,
                      color: C.onSecondaryContainer,
                      background: '#9CF1EE',
                      borderRadius: 7,
                      padding: '3px 11px',
                    }}
                  >
                    POST /song/url
                  </span>
                </div>
              </Reveal>
            </Card>

            {/* 能力网格 */}
            <Card style={{width: 780, minHeight: 486, padding: '22px 26px'}}>
              <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
                <div style={{fontFamily: MONO, fontSize: 20, color: C.primary, fontWeight: 700}}>
                  capabilities
                </div>
                <div
                  style={{
                    fontSize: 18,
                    color: C.inkVar,
                    opacity: interpolate(frame, [344, 364], [0, 1], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    }),
                  }}
                >
                  已加载 · 我的音源
                </div>
              </div>

              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(3, 1fr)',
                  gap: 11,
                  marginTop: 14,
                }}
              >
                {CAPS.map((c, i) => {
                  const p = spring({
                    frame: frame - 330 - i * 2.5,
                    fps,
                    config: {damping: 13, mass: 0.6, stiffness: 140},
                  });
                  // 340 帧之前只给虚线骨架，避免右侧空一张白卡
                  const ghost = frame < 340;
                  return (
                    <div
                      key={c.n}
                      style={{
                        position: 'relative',
                        height: 72,
                        borderRadius: 15,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 25,
                        fontWeight: 600,
                        fontFamily: FONT,
                        background: ghost ? C.surfaceLow : c.on ? C.primaryContainer : C.surfaceC,
                        border: ghost ? `1.5px dashed ${C.outline}` : 'none',
                        color: ghost ? C.outlineStrong : c.on ? C.onPrimaryContainer : C.inkVar,
                        opacity: ghost ? 0.9 : (c.on ? 1 : 0.6) * p,
                        transform: ghost ? undefined : `scale(${0.85 + 0.15 * p})`,
                      }}
                    >
                      {c.n}
                      {!c.on && !ghost && (
                        <div
                          style={{
                            position: 'absolute',
                            top: '50%',
                            left: '19%',
                            width: `${strike * 62}%`,
                            height: 2,
                            background: C.outlineStrong,
                            opacity: 0.85,
                          }}
                        />
                      )}
                    </div>
                  );
                })}
              </div>

              <div
                style={{
                  marginTop: 14,
                  fontSize: 21,
                  color: C.inkVar,
                  lineHeight: 1.5,
                  opacity: interpolate(frame, [540, 568], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  }),
                }}
              >
                没声明的能力，界面里<strong style={{color: C.ink}}> 根本不会出现 </strong>
                —— 不是灰掉，是没有。
              </div>
            </Card>
          </div>

          {/* 底部：三种运行时 */}
          <Reveal delay={606} y={16}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 16,
                padding: '14px 22px',
                borderRadius: 20,
                background: 'rgba(255,255,255,0.75)',
                border: `1px solid ${C.outline}`,
              }}
            >
              <div style={{fontSize: 23, fontWeight: 600, color: C.ink}}>打包成 .cpm，拖进去就能用</div>
              <div style={{width: 1, height: 24, background: C.outline}} />
              <Chip style={{fontSize: 20, padding: '6px 15px'}} color={C.onSecondaryContainer} bg={C.secondaryContainer}>
                HTTP · Python / Node / Go
              </Chip>
              <Chip style={{fontSize: 20, padding: '6px 15px'}}>JNI · Rust / C++</Chip>
              <Chip style={{fontSize: 20, padding: '6px 15px'}} color={C.onTertiaryContainer} bg={C.tertiaryContainer}>
                Binary · 独立可执行
              </Chip>
              <div style={{marginLeft: 'auto', fontFamily: MONO, fontSize: 19, color: C.inkVar}}>
                settingsSchema → 原生设置面板
              </div>
            </div>
          </Reveal>
        </div>

        {/* 飞进来的 .cpm 卡包 */}
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            transform: `translate(${dropX}px, ${dropY}px) translate(-50%, -50%) scale(${dropScale}) rotate(${
              drop * 8
            }deg)`,
            opacity: dropOpacity,
            pointerEvents: 'none',
            zIndex: 20,
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 14,
              padding: '16px 24px',
              borderRadius: 18,
              background: C.surface,
              border: `2px solid ${C.tertiary}`,
              boxShadow: shadow.float,
            }}
          >
            <div
              style={{
                width: 42,
                height: 42,
                borderRadius: 12,
                background: C.tertiaryContainer,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontFamily: MONO,
                fontSize: 15,
                fontWeight: 700,
                color: C.onTertiaryContainer,
              }}
            >
              ZIP
            </div>
            <div>
              <div style={{fontFamily: MONO, fontSize: 20, fontWeight: 700, color: C.ink}}>
                my-source.cpm
              </div>
              <div style={{fontSize: 16, color: C.inkVar, marginTop: 2}}>拖入即装</div>
            </div>
          </div>
        </div>
      </Stage>
    </AbsoluteFill>
  );
};
