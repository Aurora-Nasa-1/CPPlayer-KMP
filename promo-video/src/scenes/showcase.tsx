import React from 'react';
import {AbsoluteFill, Img, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {M3, rgba, FONT, DISPLAY, R, gradText, BRAND} from '../theme';
import {morphTrack, track} from '../lib/shape';
import {MorphShape, MorphClip} from '../m3/MorphShape';
import {Head, Sub, Chip, Tonal, StatusDot, Button} from '../m3/Surface';
import {WavyProgress} from '../m3/Wave';
import {Glyph, type GlyphName} from '../m3/Glyph';
import {DesktopShell, PhoneShell, ShotPanel, FloatCard, DeviceGlow} from '../stage/shells';
import {Tilt} from '../stage/Stage';
import {EASE, EASE_IN, EASE_SINE, seg, springySettle, mix} from '../lib/ease';

/* ==================================================================== */
/* S07 · 界面巡游（真机截图 + 平轴 2.5D）                                */
/* ==================================================================== */

const BEATS = [
  {label: '桌面端 · 我的音乐', title: 'Material 3 Expressive', accent: '动态取色 · 分段圆角 · 层级色阶'},
  {label: '设置 · 音源与诊断', title: '该有的信息，都在手边', accent: '音源管理 · 请求诊断 · 健康状态'},
  {label: '移动端 · 播放与歌词', title: '同一套界面，装进口袋', accent: '波形进度 · 逐行歌词 · 沉浸播放'},
];

export const S07Design: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const aIn = seg(f, 0, 26, EASE);
  const aOut = seg(f, 150, 178, EASE_IN);
  const bIn = seg(f, 162, 194, EASE);
  const bOut = seg(f, 300, 328, EASE_IN);
  const cIn = seg(f, 312, 346, EASE);

  const opA = aIn * (1 - aOut);
  const opB = bIn * (1 - bOut);
  const opC = cIn;

  // —— Beat A：桌面窗口在 2.5D 平面上被相机缓慢掠过 ——
  const winIn = springySettle(f, fps, 6, {damping: 24, stiffness: 100});
  const drift = seg(f, 0, 420, EASE_SINE);
  const camRy = mix(drift, -13, 9);
  const camX = mix(drift, 62, -58);
  const camY = mix(drift, -18, 16);

  // —— Beat B：两张设置面板悬浮 ——
  const p1 = springySettle(f, fps, 172, {damping: 24, stiffness: 118});
  const p2 = springySettle(f, fps, 194, {damping: 24, stiffness: 118});

  // —— Beat C：两台手机从下方升起 ——
  const ph1 = springySettle(f, fps, 324, {damping: 24, stiffness: 112});
  const ph2 = springySettle(f, fps, 348, {damping: 24, stiffness: 112});

  const beat = opC > 0.5 ? 2 : opB > 0.5 ? 1 : 0;

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      {/* 标题位（三拍共用，交叉淡入） */}
      <div style={{position: 'absolute', left: 0, right: 0, top: 66, textAlign: 'center'}}>
        {BEATS.map((b, i) => {
          const op = i === 0 ? opA : i === 1 ? opB : opC;
          return (
            <div key={b.label} style={{position: 'absolute', left: 0, right: 0, top: 0, opacity: op}}>
              <Chip
                variant="tonal"
                tone={i === 0 ? 'primary' : i === 1 ? 'secondary' : 'tertiary'}
                size={20}
                icon={<Glyph name={i === 0 ? 'monitor' : i === 1 ? 'sliders' : 'phone'} size={18} />}
                style={{marginBottom: 18}}
              >
                {b.label}
              </Chip>
              <Head size={58} style={{width: '100%'}}>
                {b.title}
              </Head>
              <Sub size={24} style={{marginTop: 12, letterSpacing: 1}}>
                {b.accent}
              </Sub>
            </div>
          );
        })}
      </div>

      {/* Beat A · 桌面窗口 */}
      <div style={{position: 'absolute', inset: 0, opacity: opA, pointerEvents: 'none'}}>
        <Tilt
          perspective={2600}
          rx={mix(winIn, 15, 6)}
          ry={mix(winIn, -24, camRy)}
          style={{position: 'absolute', left: 0, right: 0, top: 250}}
        >
          <div
            style={{
              display: 'flex',
              justifyContent: 'center',
              transform: `translate3d(${camX}px, ${camY + (1 - winIn) * 60}px, 0) scale(${0.9 + 0.1 * winIn})`,
            }}
          >
            <DesktopShell src="shots/shot-desktop-library.png" width={1200} glow={M3.d.primary} />
          </div>
        </Tilt>
      </div>

      {/* Beat B · 两张设置面板 */}
      <div style={{position: 'absolute', inset: 0, opacity: opB, pointerEvents: 'none'}}>
        <Tilt perspective={2200} rx={mix(p1, 16, 9)} ry={mix(p1, -34, -17)} style={{position: 'absolute', left: 200, top: 420}}>
          <div style={{transform: `translateY(${(1 - p1) * 90}px)`}}>
            <FloatCard width={720} height={420} radius={R.xl} tone={M3.d.secondary}>
              <div style={{padding: '22px 26px 0'}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                  <Glyph name="puzzle" size={22} color={M3.d.secondary} />
                  <span style={{fontSize: 21, fontWeight: 700, color: M3.d.onSurface}}>音源管理</span>
                </div>
              </div>
              <div style={{position: 'absolute', left: 26, right: 26, top: 74}}>
                <ShotPanel src="shots/shot-desktop-sources.png" width={668} ratio={0.4832} radius={14} />
              </div>
            </FloatCard>
          </div>
        </Tilt>

        <Tilt perspective={2200} rx={mix(p2, 16, 9)} ry={mix(p2, 34, 17)} style={{position: 'absolute', left: 1020, top: 450}}>
          <div style={{transform: `translateY(${(1 - p2) * 90}px)`}}>
            <FloatCard width={700} height={530} radius={R.xl} tone={M3.d.primary}>
              <div style={{padding: '22px 26px 0'}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                  <Glyph name="shield" size={22} color={M3.d.primary} />
                  <span style={{fontSize: 21, fontWeight: 700, color: M3.d.onSurface}}>请求诊断</span>
                  <div style={{marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8}}>
                    <StatusDot tone="ok" size={11} />
                    <span style={{fontSize: 18, color: M3.d.secondary}}>综合健康</span>
                  </div>
                </div>
              </div>
              <div style={{position: 'absolute', left: 26, right: 26, top: 74}}>
                <ShotPanel src="shots/shot-desktop-diagnostics.png" width={648} ratio={0.6829} radius={14} />
              </div>
            </FloatCard>
          </div>
        </Tilt>
      </div>

      {/* Beat C · 两台手机 */}
      <div style={{position: 'absolute', inset: 0, opacity: opC, pointerEvents: 'none'}}>
        <Tilt perspective={2400} rx={mix(ph1, 14, 5)} ry={mix(ph1, 46, 15)} style={{position: 'absolute', left: 470, top: 268}}>
          <div style={{transform: `translateY(${(1 - ph1) * 140}px)`}}>
            <DeviceGlow width={520} height={150} color={M3.d.primary} opacity={0.55} style={{top: 700}} />
            <PhoneShell src="shots/shot-android-player.jpg" width={344} glow={M3.d.primary} />
          </div>
        </Tilt>
        <Tilt perspective={2400} rx={mix(ph2, 14, 5)} ry={mix(ph2, -46, -15)} style={{position: 'absolute', left: 1104, top: 300}}>
          <div style={{transform: `translateY(${(1 - ph2) * 140}px)`}}>
            <DeviceGlow width={520} height={150} color={M3.d.secondary} opacity={0.5} style={{top: 700}} />
            <PhoneShell src="shots/shot-android-lyrics.jpg" width={330} glow={M3.d.secondary} />
          </div>
        </Tilt>
      </div>

      {/* 底部细进度：三拍进度 */}
      <div style={{position: 'absolute', left: 0, right: 0, bottom: 54, display: 'flex', justifyContent: 'center', gap: 10}}>
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            style={{
              width: 74,
              height: 5,
              borderRadius: 999,
              background: beat === i ? M3.d.primary : rgba(M3.d.onSurface, 0.16),
            }}
          />
        ))}
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S08 · 一套内核，多端运行（等距 2.5D 架构图）                          */
/* ==================================================================== */

const NODES = [
  {id: 'app', label: 'app', sub: 'Compose UI · 界面与交互', x: 340, y: 400, tone: M3.d.primary, icon: 'monitor' as GlyphName},
  {id: 'core', label: 'core', sub: '播放内核 · 音源插件 · 缓存', x: 960, y: 470, tone: M3.d.secondary, icon: 'cube' as GlyphName},
  {id: 'android', label: 'app-android', sub: '平台入口 · MediaSession', x: 1580, y: 400, tone: M3.d.tertiary, icon: 'phone' as GlyphName},
];

const PLATFORMS: Array<{name: string; icon: GlyphName; pkg: string}> = [
  {name: 'Android', icon: 'android', pkg: 'APK'},
  {name: 'Windows', icon: 'windows', pkg: 'MSI'},
  {name: 'macOS', icon: 'apple', pkg: 'Dmg'},
  {name: 'Linux', icon: 'terminal', pkg: 'Deb'},
];

export const S08Kmp: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const head = seg(f, 4, 32, EASE);
  const coreIn = springySettle(f, fps, 16, {damping: 23, stiffness: 115});
  const sideIn = [springySettle(f, fps, 40, {damping: 24, stiffness: 118}), springySettle(f, fps, 54, {damping: 24, stiffness: 118})];
  const linkIn = seg(f, 60, 108, EASE);
  const platIn = PLATFORMS.map((_, i) => springySettle(f, fps, 118 + i * 13, {damping: 20, stiffness: 130}));
  const capIn = seg(f, 186, 218, EASE);

  const tiltP = seg(f, 0, 90, EASE_SINE);

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      <div style={{position: 'absolute', left: 0, right: 0, top: 74, textAlign: 'center'}}>
        <Chip variant="tonal" tone="secondary" size={20} icon={<Glyph name="layers" size={18} />} style={{opacity: head, marginBottom: 18}}>
          Kotlin Multiplatform
        </Chip>
        <Head size={62} style={{opacity: head, transform: `translateY(${(1 - head) * 22}px)`}}>
          一套内核，<span style={{color: M3.d.secondary}}>四个平台</span>
        </Head>
      </div>

      <Tilt perspective={2600} rx={mix(tiltP, 34, 13)} ry={mix(tiltP, -22, -9)} style={{position: 'absolute', inset: 0}}>
        <div style={{position: 'relative', width: 1920, height: 1080}}>
          {/* 连接线 */}
          <svg width={1920} height={1080} viewBox="0 0 1920 1080" style={{position: 'absolute', inset: 0}}>
            {NODES.filter((n) => n.id !== 'core').map((n) => {
              const len = Math.hypot(n.x - 960, n.y - 470);
              return (
                <line
                  key={n.id}
                  x1={960}
                  y1={470}
                  x2={n.x}
                  y2={n.y}
                  stroke={rgba(M3.d.outline, 0.8)}
                  strokeWidth={2.5}
                  strokeDasharray={len}
                  strokeDashoffset={len * (1 - linkIn)}
                />
              );
            })}
            {/* core → 平台层 */}
            <line
              x1={960}
              y1={520}
              x2={960}
              y2={700}
              stroke={rgba(M3.d.secondary, 0.8)}
              strokeWidth={2.5}
              strokeDasharray={200}
              strokeDashoffset={200 * (1 - linkIn)}
            />
          </svg>

          {/* 三个模块节点 */}
          {NODES.map((n, i) => {
            const p = n.id === 'core' ? coreIn : sideIn[i === 0 ? 0 : 1];
            const z = n.id === 'core' ? 60 : 0;
            return (
              <div
                key={n.id}
                style={{
                  position: 'absolute',
                  left: n.x - 200,
                  top: n.y - 62,
                  width: 400,
                  height: 124,
                  transform: `translate3d(0,0,${z}px) scale(${0.86 + 0.14 * p})`,
                  opacity: p,
                  borderRadius: R.xl,
                  background: `linear-gradient(150deg, ${rgba(n.tone, 0.22)}, ${rgba(M3.d.surfaceHigh, 0.94)})`,
                  border: `1.5px solid ${rgba(n.tone, 0.42)}`,
                  boxShadow: `0 40px 80px -40px rgba(0,0,0,0.9), 0 0 0 1px ${rgba('#FFFFFF', 0.05)}`,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 16,
                  padding: '0 22px',
                  boxSizing: 'border-box',
                }}
              >
                <Tonal size={58} tone={n.id === 'app' ? 'primary' : n.id === 'core' ? 'secondary' : 'tertiary'}>
                  <Glyph name={n.icon} size={29} />
                </Tonal>
                <div>
                  <div style={{fontSize: 27, fontWeight: 700, color: n.tone, fontFamily: "'Cascadia Mono',Consolas,monospace"}}>
                    {n.label}
                  </div>
                  <div style={{fontSize: 18, color: M3.d.onSurfaceVariant, marginTop: 3}}>{n.sub}</div>
                </div>
              </div>
            );
          })}

          {/* 平台徽标 */}
          {PLATFORMS.map((p, i) => {
            const t = platIn[i];
            return (
              <div
                key={p.name}
                style={{
                  position: 'absolute',
                  left: 190 + i * 400,
                  top: 700,
                  width: 340,
                  height: 128,
                  transform: `translateY(${(1 - t) * 46}px) scale(${0.9 + 0.1 * t})`,
                  opacity: t,
                  borderRadius: R.xl,
                  background: rgba(M3.d.surfaceHigh, 0.9),
                  border: `1px solid ${rgba(M3.d.outlineVariant, 0.75)}`,
                  boxShadow: '0 30px 60px -34px rgba(0,0,0,0.9)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 16,
                  padding: '0 22px',
                  boxSizing: 'border-box',
                }}
              >
                <Glyph name={p.icon} size={40} color={M3.d.onSurface} />
                <div>
                  <div style={{fontSize: 26, fontWeight: 700, color: M3.d.onSurface}}>{p.name}</div>
                  <div style={{fontSize: 18, color: M3.d.onSurfaceVariant, marginTop: 3}}>{p.pkg} 安装包</div>
                </div>
              </div>
            );
          })}
        </div>
      </Tilt>

      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 60,
          textAlign: 'center',
          opacity: capIn,
          transform: `translateY(${(1 - capIn) * 16}px)`,
        }}
      >
        <span style={{fontSize: 25, color: M3.d.onSurfaceVariant, letterSpacing: 1}}>
          共用同一套内核与界面代码 · <span style={{color: M3.d.onSurface, fontWeight: 700}}>功能同步迭代</span>
        </span>
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S09 · 特色能力（卡片轮播 + 形状形变）                                 */
/* ==================================================================== */

const FEATURES: Array<{
  icon: GlyphName;
  title: string;
  desc: string;
  tone: string;
  shape: 'cookie9' | 'flower8' | 'sunny12';
  metric: string;
  metricSub: string;
  tags: string[];
}> = [
  {
    icon: 'broadcast',
    title: '本地服务器 · HTTP 流推送',
    desc: '把正在播放的音频推给外部接收端，游戏内 radio 也能接。',
    tone: M3.d.primary,
    shape: 'cookie9',
    metric: '8420',
    metricSub: '标准端口 · v1 集成契约',
    tags: ['断点续传', 'Content-Range 透传', '免配置对接'],
  },
  {
    icon: 'server',
    title: '静默模式 · 只做服务器',
    desc: '本机音量恒为 0，只对外供流，适合当作后台音源服务。',
    tone: M3.d.secondary,
    shape: 'flower8',
    metric: '0',
    metricSub: '本机输出音量',
    tags: ['后台供流', '不占用声卡', '可随时切回'],
  },
  {
    icon: 'layers',
    title: '读透缓存 · 写操作自动失效',
    desc: '读类请求命中即返回，过期自动回源；点赞评论等写操作不缓存。',
    tone: M3.d.tertiary,
    shape: 'sunny12',
    metric: '≈92%',
    metricSub: '读请求缓存命中率',
    tags: ['读透缓存', '写后失效', '统计可查'],
  },
];

export const S09Features: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const head = seg(f, 2, 28, EASE);
  const PER = 74;
  const idx = Math.min(FEATURES.length - 1, Math.floor(Math.max(0, f - 24) / PER));
  const localP = Math.min(1, Math.max(0, (f - 24 - idx * PER) / PER));

  const cardIn = springySettle(f, fps, 18, {damping: 24, stiffness: 118});
  const capIn = seg(f, 210, 240, EASE);

  const shapeP = seg(f, 24, 24 + PER * 3, EASE_SINE);
  const shape = morphTrack(
    track([0, 'cookie9'], [0.34, 'flower8'], [0.67, 'sunny12'], [1, 'circle']),
    shapeP,
  );

  const cur = FEATURES[idx];

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      <div style={{position: 'absolute', left: 0, right: 0, top: 84, textAlign: 'center'}}>
        <Chip variant="tonal" tone="primary" size={20} icon={<Glyph name="bolt" size={18} />} style={{opacity: head, marginBottom: 18}}>
          面向开发者与进阶用户
        </Chip>
        <Head size={60} style={{opacity: head, transform: `translateY(${(1 - head) * 22}px)`}}>
          还有一些<span style={{color: M3.d.primary}}>顺手的能力</span>
        </Head>
      </div>

      <Tilt perspective={2400} rx={mix(cardIn, 14, 0)} ry={mix(cardIn, -14, 0)} style={{position: 'absolute', left: 360, top: 300}}>
        <div
          style={{
            width: 1200,
            height: 480,
            borderRadius: R.xxl,
            background: `linear-gradient(150deg, ${rgba(cur.tone, 0.16)}, ${rgba(M3.d.surfaceHigh, 0.94)} 46%, ${rgba(
              M3.d.surfaceLow,
              0.96,
            )})`,
            border: `1.5px solid ${rgba(cur.tone, 0.34)}`,
            boxShadow: `0 80px 150px -60px rgba(0,0,0,0.95), 0 0 160px -60px ${rgba(cur.tone, 0.55)}`,
            padding: 48,
            boxSizing: 'border-box',
            display: 'flex',
            alignItems: 'center',
            gap: 56,
            transform: `scale(${0.94 + 0.06 * cardIn})`,
            opacity: cardIn,
          }}
        >
          {/* 形变图标容器。⚠️ 两个 MorphShape 必须各自包一层 absolute ——
              渲染出来是 <svg display:block>，在普通流里会**上下堆叠**，
              第二个就被挤到容器外面去了（看着像凭空多出一个形状）。 */}
          <div style={{position: 'relative', width: 260, height: 260, flexShrink: 0}}>
            <div style={{position: 'absolute', inset: 0}}>
              <MorphShape shape={shape} size={260} id="s9-blob" fill={rgba(cur.tone, 0.2)} />
            </div>
            <div style={{position: 'absolute', inset: -10}}>
              <MorphShape
                shape={shape}
                size={280}
                id="s9-line"
                stroke={rgba(cur.tone, 0.55)}
                strokeWidth={1.8}
              />
            </div>
            <div style={{position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
              <Glyph name={cur.icon} size={96} color={cur.tone} strokeWidth={1.5} />
            </div>
          </div>

          <div style={{flex: 1, minWidth: 0}}>
            <div
              key={cur.title}
              style={{
                fontSize: 44,
                fontWeight: 800,
                color: M3.d.onSurface,
                fontFamily: DISPLAY,
                letterSpacing: -0.6,
                opacity: Math.min(1, localP * 3.2),
                transform: `translateY(${(1 - Math.min(1, localP * 3.2)) * 14}px)`,
              }}
            >
              {cur.title}
            </div>
            <div
              style={{
                fontSize: 24,
                color: M3.d.onSurfaceVariant,
                marginTop: 14,
                lineHeight: 1.5,
                opacity: Math.min(1, localP * 3.2),
              }}
            >
              {cur.desc}
            </div>

            <div style={{display: 'flex', alignItems: 'flex-end', gap: 16, marginTop: 30}}>
              <span style={{fontSize: 76, fontWeight: 800, color: cur.tone, fontFamily: DISPLAY, lineHeight: 0.9}}>
                {cur.metric}
              </span>
              <span style={{fontSize: 20, color: M3.d.onSurfaceVariant, paddingBottom: 8}}>{cur.metricSub}</span>
            </div>

            <div style={{display: 'flex', gap: 10, marginTop: 26, flexWrap: 'wrap'}}>
              {cur.tags.map((t) => (
                <Chip key={t} variant="outlined" tone="neutral" size={18}>
                  {t}
                </Chip>
              ))}
            </div>
          </div>
        </div>
      </Tilt>

      {/* 底部三段进度 */}
      <div style={{position: 'absolute', left: 0, right: 0, bottom: 96, display: 'flex', justifyContent: 'center', gap: 12}}>
        {FEATURES.map((ft, i) => (
          <div key={ft.title} style={{width: 180, height: 6, borderRadius: 999, background: rgba(M3.d.onSurface, 0.14), overflow: 'hidden'}}>
            <div
              style={{
                width: '100%',
                height: '100%',
                background: ft.tone,
                transform: `scaleX(${i < idx ? 1 : i === idx ? localP : 0})`,
                transformOrigin: 'left center',
              }}
            />
          </div>
        ))}
      </div>

      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 44,
          textAlign: 'center',
          opacity: capIn,
        }}
      >
        <span style={{fontSize: 22, color: M3.d.onSurfaceVariant, letterSpacing: 1}}>
          第三方软件按公开的 v1 集成契约直接对接
        </span>
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S10 · 结尾卡                                                          */
/* ==================================================================== */

export const S10End: React.FC = () => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const S = 300;

  const burst = seg(f, 0, 90, EASE);
  const iconIn = seg(f, 26, 76, EASE);
  const iconScale = 0.86 + 0.14 * springySettle(f, fps, 20, {damping: 22, stiffness: 110});
  const titleP = Math.min(1, springySettle(f, fps, 58, {damping: 21, stiffness: 108}));
  const tagP = seg(f, 78, 108, EASE);
  const platP = seg(f, 94, 126, EASE);
  const btnP = seg(f, 112, 146, EASE);

  const shape = morphTrack(
    track([0, 'sunny12'], [0.45, 'flower8'], [0.8, 'cookie9'], [1, 'squircle']),
    seg(f, 0, 110, EASE_SINE),
  );

  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', fontFamily: FONT}}>
      {/* 向外扩散的形变波纹 */}
      <div style={{position: 'absolute', left: '50%', top: '50%', translate: '-50% -50%'}}>
        {[0, 1, 2].map((i) => {
          const p = seg(f, i * 14, 110 + i * 14, EASE);
          return (
            <div key={i} style={{position: 'absolute', left: '50%', top: '50%', translate: '-50% -50%', opacity: (1 - p) * 0.32}}>
              <MorphShape
                shape={shape}
                size={S * (1.1 + p * 2.6)}
                id={`s10-ripple-${i}`}
                stroke={rgba(i === 1 ? M3.d.secondary : M3.d.primary, 0.8)}
                strokeWidth={1.6}
                rot={p * 0.5 * (i % 2 === 0 ? 1 : -1)}
              />
            </div>
          );
        })}
      </div>

      <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
        <div style={{position: 'relative', width: S, height: S, transform: `scale(${iconScale})`}}>
          <MorphClip shape={shape} size={S} style={{position: 'absolute', inset: 0, opacity: iconIn}}>
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

        <div
          style={{
            fontFamily: DISPLAY,
            fontSize: 108,
            fontWeight: 800,
            letterSpacing: -3,
            lineHeight: 1,
            marginTop: 40,
            opacity: titleP,
            transform: `translateY(${(1 - titleP) * 32}px)`,
            ...gradText,
          }}
        >
          CPPlayer
        </div>
        <div
          style={{
            fontSize: 32,
            color: M3.d.onSurfaceVariant,
            marginTop: 18,
            letterSpacing: 4,
            opacity: tagP,
            transform: `translateY(${(1 - tagP) * 20}px)`,
          }}
        >
          本体只做播放 · 音源即插即用
        </div>

        <div style={{display: 'flex', gap: 14, marginTop: 34, opacity: platP, transform: `translateY(${(1 - platP) * 18}px)`}}>
          {PLATFORMS.map((p) => (
            <Chip key={p.name} variant="tonal" tone="neutral" size={20} icon={<Glyph name={p.icon} size={19} />}>
              {p.name}
            </Chip>
          ))}
        </div>

        <div style={{marginTop: 42, opacity: btnP, transform: `translateY(${(1 - btnP) * 18}px)`}}>
          <Button variant="filled" tone="primary" size={26} icon={<Glyph name="download" size={23} />}>
            下载体验
          </Button>
        </div>
      </div>
    </AbsoluteFill>
  );
};
