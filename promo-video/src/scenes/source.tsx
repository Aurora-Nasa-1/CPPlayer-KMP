import React from 'react';
import {AbsoluteFill, useCurrentFrame, useVideoConfig} from 'remotion';
import {M3, rgba, FONT, R, segRadius, surfaceLevel} from '../theme';
import {MorphShape} from '../m3/MorphShape';
import {morphTrack, track} from '../lib/shape';
import {Glyph, type GlyphName} from '../m3/Glyph';
import {Head, Sub, Chip, StatusDot, Tonal} from '../m3/Surface';
import {WavyProgress, CacheBar} from '../m3/Wave';
import {Tilt} from '../stage/Stage';
import {EASE, EASE_IN, EASE_IO, EASE_SINE, seg, springySettle, mix} from '../lib/ease';

/* ==================================================================== */
/* 公共：音源色调 / 面板 / 槽位行                                        */
/* ==================================================================== */

type Tone = 'primary' | 'secondary' | 'tertiary';

const TONE: Record<Tone, {fg: string; cont: string; on: string}> = {
  primary: {fg: M3.d.primary, cont: M3.d.primaryContainer, on: M3.d.onPrimaryContainer},
  secondary: {fg: M3.d.secondary, cont: M3.d.secondaryContainer, on: M3.d.onSecondaryContainer},
  tertiary: {fg: M3.d.tertiary, cont: M3.d.tertiaryContainer, on: M3.d.onTertiaryContainer},
};

const SOURCES: Array<{name: string; meta: string; tone: Tone}> = [
  {name: '音源 A', meta: 'provider-0 · 128ms', tone: 'primary'},
  {name: '音源 B', meta: 'provider-1 · 142ms', tone: 'secondary'},
  {name: '音源 C', meta: 'provider-2 · 96ms', tone: 'tertiary'},
];

const PANEL_W = 940;
const PAD = 26;
const HEAD_H = 40;
const HEAD_GAP = 20;
const SLOT_H = 104;
const SLOT_GAP = 4;

const panelHeight = () => PAD * 2 + HEAD_H + HEAD_GAP + 3 * SLOT_H + 2 * SLOT_GAP;
/** 第 i 个槽位中心的 Y（相对面板顶部） */
const slotCenterY = (i: number) => PAD + HEAD_H + HEAD_GAP + i * (SLOT_H + SLOT_GAP) + SLOT_H / 2;

const Panel: React.FC<{
  readonly width?: number;
  readonly title: string;
  readonly right?: React.ReactNode;
  readonly glow?: string;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({width = PANEL_W, title, right, glow, style, children}) => (
  <div
    style={{
      width,
      padding: PAD,
      boxSizing: 'border-box',
      borderRadius: R.xl,
      background: `linear-gradient(160deg, ${rgba(M3.d.surfaceHigh, 0.94)}, ${rgba(M3.d.surfaceLow, 0.96)})`,
      border: `1px solid ${rgba(M3.d.outlineVariant, 0.8)}`,
      boxShadow: `0 70px 130px -55px rgba(0,0,0,0.95), inset 0 1px 0 ${rgba('#FFFFFF', 0.07)}${
        glow ? `, 0 0 130px -40px ${rgba(glow, 0.5)}` : ''
      }`,
      fontFamily: FONT,
      ...style,
    }}
  >
    <div style={{height: HEAD_H, display: 'flex', alignItems: 'center', gap: 12, marginBottom: HEAD_GAP}}>
      <Glyph name="puzzle" size={27} color={M3.d.primary} />
      <span style={{fontSize: 25, fontWeight: 700, color: M3.d.onSurface, letterSpacing: 0.3}}>{title}</span>
      <div style={{marginLeft: 'auto'}}>{right}</div>
    </div>
    {children}
  </div>
);

/** 一个音源槽位（有内容 / 空槽） */
const Slot: React.FC<{
  readonly index: number;
  readonly total: number;
  readonly tone: Tone;
  readonly name: string;
  readonly meta: string;
  readonly state?: 'active' | 'standby' | 'empty' | 'fail' | 'probing';
  readonly appear?: number;
  readonly dim?: number;
  readonly ring?: number;
  readonly style?: React.CSSProperties;
  readonly children?: React.ReactNode;
}> = ({index, total, tone, name, meta, state = 'standby', appear = 1, dim = 1, ring = 0, style, children}) => {
  const t = TONE[tone];
  const empty = state === 'empty';
  const active = state === 'active';
  const fail = state === 'fail';
  const probing = state === 'probing';
  return (
    <div
      style={{
        height: SLOT_H,
        borderRadius: segRadius(index, total),
        background: empty
          ? 'transparent'
          : active
            ? rgba(t.fg, 0.16)
            : fail
              ? rgba(M3.d.errorContainer, 0.42)
              : surfaceLevel(2),
        border: empty
          ? `2px dashed ${rgba(M3.d.outlineVariant, 0.85)}`
          : active
            ? `1.5px solid ${rgba(t.fg, 0.5)}`
            : '1.5px solid transparent',
        boxShadow: ring > 0 ? `0 0 0 ${3 * ring}px ${rgba(t.fg, 0.32 * ring)}` : undefined,
        display: 'flex',
        alignItems: 'center',
        padding: '0 20px',
        gap: 18,
        boxSizing: 'border-box',
        opacity: appear * dim,
        position: 'relative',
        overflow: 'hidden',
        ...style,
      }}
    >
      {empty ? (
        <>
          <Tonal size={52} tone="surface" style={{background: 'transparent', border: `1.5px dashed ${M3.d.outlineVariant}`}}>
            <Glyph name="plus" size={24} color={M3.d.outline} />
          </Tonal>
          <div style={{fontSize: 24, color: M3.d.outline}}>空槽位 · 等待导入模块</div>
        </>
      ) : (
        <>
          <Tonal size={54} tone={tone}>
            <Glyph name="cube" size={27} />
          </Tonal>
          <div style={{flex: 1, minWidth: 0}}>
            <div style={{fontSize: 27, fontWeight: 600, color: active ? t.fg : M3.d.onSurface}}>{name}</div>
            <div style={{fontSize: 19, color: M3.d.onSurfaceVariant, marginTop: 3}}>{meta}</div>
          </div>
          {children}
          {probing ? <WavyProgress progress={1} width={120} height={16} phase={0} /> : null}
          {fail ? <Glyph name="x" size={28} color={M3.d.error} strokeWidth={2.6} /> : null}
        </>
      )}
    </div>
  );
};

/** 状态标签 */
const StateTag: React.FC<{
  readonly text: string;
  readonly color: string;
  readonly dot?: boolean;
  readonly appear?: number;
}> = ({text, color, dot, appear = 1}) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 9,
      opacity: appear,
      transform: `translateX(${(1 - appear) * 14}px)`,
    }}
  >
    {dot ? <StatusDot tone="ok" size={11} /> : null}
    <span style={{fontSize: 20, fontWeight: 600, color}}>{text}</span>
  </div>
);

/* ==================================================================== */
/* S03 · 导入 .cpm 模块                                                  */
/* ==================================================================== */

export const S03Import: React.FC = () => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();

  const head = seg(f, 4, 34, EASE);
  const bullets = [0, 1, 2].map((i) => seg(f, 30 + i * 9, 58 + i * 9, EASE));

  const panelIn = springySettle(f, fps, 8, {damping: 24, stiffness: 115});
  const slotsIn = [0, 1, 2].map((i) => springySettle(f, fps, 16 + i * 7, {damping: 25, stiffness: 130}));

  const cardIn = springySettle(f, fps, 22, {damping: 22, stiffness: 105});
  const flyP = seg(f, 66, 146, EASE_IO);
  const cardOut = seg(f, 142, 164, EASE_IN);
  const slotFill = seg(f, 148, 182, EASE);
  const capIn = seg(f, 198, 230, EASE);

  const panelLeft = 880;
  const panelTop = 560 - panelHeight() / 2;
  const targetX = panelLeft + PANEL_W / 2;
  const targetY = panelTop + slotCenterY(0);

  const CARD_W = 320;
  const CARD_H = 214;
  const cardScale = mix(flyP, 1, 0.36);
  const cardX = mix(flyP, 250, targetX - (CARD_W * 0.36) / 2) + (1 - cardIn) * -160;
  const bob = flyP < 0.02 ? Math.sin(f * 0.09) * 7 : 0;
  const cardY =
    mix(flyP, 830, targetY - (CARD_H * 0.36) / 2) - Math.sin(flyP * Math.PI) * 190 + bob;
  const cardRot = mix(flyP, -13, 0);

  const cardShape = morphTrack(
    track([0, 'squircle'], [0.55, 'cookie6'], [1, 'circle']),
    flyP,
  );

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      {/* 左栏文案 */}
      <div style={{position: 'absolute', left: 130, top: 232, width: 640}}>
        <Chip variant="tonal" tone="primary" size={21} icon={<Glyph name="cube" size={19} />} style={{opacity: head}}>
          音源模块 · .cpm
        </Chip>
        <Head size={74} style={{marginTop: 24, opacity: head, transform: `translateY(${(1 - head) * 26}px)`}}>
          音源，
          <br />
          只是<span style={{color: M3.d.primary}}>一块插件</span>
        </Head>
        <Sub size={26} style={{marginTop: 22, opacity: head, transform: `translateY(${(1 - head) * 18}px)`}}>
          本体不内置任何数据源。内容能力
          <br />
          完全由导入的音源模块决定。
        </Sub>

        <div style={{display: 'flex', flexDirection: 'column', gap: 18, marginTop: 40}}>
          {[
            {icon: 'cube' as GlyphName, text: '选择 .cpm 文件导入即用'},
            {icon: 'bolt' as GlyphName, text: '首个 Provider 自动激活'},
            {icon: 'layers' as GlyphName, text: '可并存多个音源随时切换'},
          ].map((b, i) => (
            <div
              key={b.text}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 14,
                opacity: bullets[i],
                transform: `translateX(${(1 - bullets[i]) * -22}px)`,
              }}
            >
              <Tonal size={40} tone="primary" radius={12}>
                <Glyph name={b.icon} size={21} />
              </Tonal>
              <span style={{fontSize: 24, color: M3.d.onSurfaceVariant}}>{b.text}</span>
            </div>
          ))}
        </div>
      </div>

      {/* 右侧：Provider 容器（2.5D） */}
      <Tilt
        perspective={2200}
        rx={mix(panelIn, 10, 0)}
        ry={mix(panelIn, -13, 0)}
        style={{position: 'absolute', left: panelLeft, top: panelTop, width: PANEL_W, height: panelHeight()}}
      >
        <Panel
          title="Provider 容器"
          glow={M3.d.primary}
          right={
            <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
              <StatusDot tone="ok" size={11} />
              <span style={{fontSize: 19, color: M3.d.onSurfaceVariant}}>1 / 3 已激活</span>
            </div>
          }
        >
          <div style={{display: 'flex', flexDirection: 'column', gap: SLOT_GAP}}>
            {SOURCES.map((s, i) => (
              <Slot
                key={s.name}
                index={i}
                total={3}
                tone={s.tone}
                name={s.name}
                meta={s.meta}
                state={i === 0 ? (slotFill > 0.05 ? 'active' : 'empty') : 'empty'}
                appear={slotsIn[i]}
                ring={i === 0 ? slotFill : 0}
              >
                {i === 0 ? (
                  <>
                    <StateTag text="已激活" color={M3.d.primary} dot appear={slotFill} />
                    <span style={{fontSize: 20, color: M3.d.onSurfaceVariant, opacity: slotFill}}>健康 正常</span>
                  </>
                ) : null}
              </Slot>
            ))}
          </div>
        </Panel>
      </Tilt>

      {/* 飞行中的 .cpm 模块 */}
      <div
        style={{
          position: 'absolute',
          left: cardX,
          top: cardY,
          width: CARD_W,
          height: CARD_H,
          transform: `rotate(${cardRot}deg) scale(${cardScale})`,
          transformOrigin: 'center center',
          opacity: Math.min(1, cardIn) * (1 - cardOut),
        }}
      >
        <div style={{position: 'absolute', inset: 0, opacity: flyP > 0.02 ? 1 : 0}}>
          <MorphShape shape={cardShape} size={CARD_W} id="s3-card" fill={rgba(M3.d.primaryContainer, 0.9)} />
        </div>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 12,
            fontFamily: FONT,
          }}
        >
          <Glyph name="cube" size={58} color={M3.d.onPrimaryContainer} />
          <span style={{fontSize: 30, fontWeight: 700, color: M3.d.onPrimaryContainer}}>package.cpm</span>
          <span style={{fontSize: 19, color: rgba(M3.d.onPrimaryContainer, 0.72)}}>音源模块 · 1.2 MB</span>
        </div>
      </div>

      {/* 底部说明 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 900,
          textAlign: 'center',
          opacity: capIn,
          transform: `translateY(${(1 - capIn) * 18}px)`,
        }}
      >
        <span style={{fontSize: 27, color: M3.d.onSurfaceVariant, letterSpacing: 1}}>
          导入即用 · <span style={{color: M3.d.primary, fontWeight: 700}}>首个 Provider 自动激活</span>
        </span>
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S04 · 多音源热插拔                                                    */
/* ==================================================================== */

export const S04Switch: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const head = seg(f, 4, 34, EASE);
  const panelIn = springySettle(f, fps, 8, {damping: 24, stiffness: 115});
  const rowsIn = [0, 1, 2].map((i) => springySettle(f, fps, 18 + i * 8, {damping: 25, stiffness: 130}));

  // 选中态在 0 → 1 → 2 之间迁移
  const move1 = seg(f, 78, 116, EASE_IO);
  const move2 = seg(f, 150, 188, EASE_IO);
  const sel = move1 + move2; // 0 → 1 → 2

  const capIn = seg(f, 200, 232, EASE);

  const panelLeft = 880;
  const panelTop = 560 - panelHeight() / 2;
  const rowH = SLOT_H + SLOT_GAP;

  const activeIdx = Math.round(sel);
  // ⚠️ 这个 ring 是「内容区」的绝对定位子元素，而 slotCenterY 的基准是**面板顶部**
  // —— 两者差了一个 PAD+HEAD_H+HEAD_GAP。第一版忘了减掉，选中框整整偏下一行。
  const ringTop = sel * rowH - 3;

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      <div style={{position: 'absolute', left: 130, top: 268, width: 660}}>
        <Chip variant="tonal" tone="secondary" size={21} icon={<Glyph name="swap" size={19} />} style={{opacity: head}}>
          多音源并存
        </Chip>
        <Head size={74} style={{marginTop: 24, opacity: head, transform: `translateY(${(1 - head) * 26}px)`}}>
          随时
          <span style={{color: M3.d.secondary}}>热插拔</span>
          <br />
          切换音源
        </Head>
        <Sub size={26} style={{marginTop: 22, opacity: head, transform: `translateY(${(1 - head) * 18}px)`}}>
          同时导入多个模块，随时切换。
          <br />
          每个音源的缓存相互独立，切换不互相污染。
        </Sub>
      </div>

      <Tilt
        perspective={2200}
        rx={mix(panelIn, 10, 0)}
        ry={mix(panelIn, -13, 0)}
        style={{position: 'absolute', left: panelLeft, top: panelTop, width: PANEL_W, height: panelHeight()}}
      >
        <Panel
          title="Provider 容器"
          glow={M3.d.secondary}
          right={<span style={{fontSize: 19, color: M3.d.onSurfaceVariant}}>3 个音源已导入</span>}
        >
          <div style={{position: 'relative', display: 'flex', flexDirection: 'column', gap: SLOT_GAP}}>
            {/* 选中框：在行之间滑动 */}
            <div
              style={{
                position: 'absolute',
                left: -3,
                top: ringTop,
                width: PANEL_W - PAD * 2 + 6,
                height: SLOT_H + 6,
                borderRadius: segRadius(activeIdx, 3),
                boxShadow: `0 0 0 3px ${rgba(TONE[SOURCES[activeIdx].tone].fg, 0.42)}`,
                opacity: seg(f, 60, 90, EASE),
                pointerEvents: 'none',
              }}
            />
            {SOURCES.map((s, i) => {
              const isActive = activeIdx === i;
              const activation = seg(f, i === 0 ? 0 : i === 1 ? 96 : 168, i === 0 ? 20 : i === 1 ? 116 : 188, EASE);
              return (
                <Slot
                  key={s.name}
                  index={i}
                  total={3}
                  tone={s.tone}
                  name={s.name}
                  meta={s.meta}
                  state={isActive ? 'active' : 'standby'}
                  appear={rowsIn[i]}
                  dim={isActive ? 1 : 0.82}
                >
                  <StateTag
                    text={isActive ? '使用中' : '待命'}
                    color={isActive ? s.tone === 'primary' ? M3.d.primary : s.tone === 'secondary' ? M3.d.secondary : M3.d.tertiary : M3.d.onSurfaceVariant}
                    dot={isActive}
                    appear={isActive ? activation : 1}
                  />
                </Slot>
              );
            })}
          </div>
        </Panel>
      </Tilt>

      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 900,
          textAlign: 'center',
          opacity: capIn,
          transform: `translateY(${(1 - capIn) * 18}px)`,
        }}
      >
        <span style={{fontSize: 27, color: M3.d.onSurfaceVariant, letterSpacing: 1}}>
          点一下即换源 · <span style={{color: M3.d.secondary, fontWeight: 700}}>无需重启，无需重装</span>
        </span>
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S05 · 多音源容灾                                                      */
/* ==================================================================== */

const CARD_W = 400;
const CARD_H = 216;
const CARD_Y = 626;
const CARD_XS = [300, 960, 1620];
const NODE_Y = 306;

export const S05Failover: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const head = seg(f, 4, 32, EASE);
  const cardsIn = [0, 1, 2].map((i) => springySettle(f, fps, 14 + i * 8, {damping: 25, stiffness: 125}));

  // 三次尝试：A 失败 → B 失败 → C 成功
  const try1 = seg(f, 56, 96, EASE_IO);
  const r1 = seg(f, 96, 116, EASE);
  const try2 = seg(f, 118, 158, EASE_IO);
  const r2 = seg(f, 158, 178, EASE);
  const try3 = seg(f, 180, 220, EASE_IO);
  const r3 = seg(f, 220, 240, EASE);
  const capIn = seg(f, 232, 262, EASE);

  const tries = [try1, try2, try3];
  const results = [r1, r2, r3];

  // 请求包：沿「节点 → 当前音源」的路径飞行
  const packet = (() => {
    for (let i = 0; i < 3; i++) {
      if (tries[i] > 0 && tries[i] < 1) return {i, p: tries[i], show: true};
    }
    return {i: -1, p: 0, show: false};
  })();

  const packetPos = (() => {
    if (!packet.show) return {x: 0, y: 0};
    const tx = CARD_XS[packet.i];
    const ty = CARD_Y - CARD_H / 2;
    return {
      x: mix(packet.p, 960, tx),
      y: mix(packet.p, NODE_Y, ty),
    };
  })();

  const ledTone = r3 > 0.6 ? 'ok' : r2 > 0.4 ? 'bad' : r1 > 0.4 ? 'warn' : 'ok';

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      <div style={{position: 'absolute', left: 0, right: 0, top: 78, textAlign: 'center'}}>
        <Chip
          variant="tonal"
          tone="tertiary"
          size={21}
          icon={<Glyph name="shield" size={19} />}
          style={{opacity: head, marginBottom: 20}}
        >
          多音源容灾
        </Chip>
        <Head size={64} style={{opacity: head, transform: `translateY(${(1 - head) * 24}px)`}}>
          当前源挂了，<span style={{color: M3.d.tertiary}}>自动换下一个</span>
        </Head>
      </div>

      {/* 播放请求节点 */}
      <div
        style={{
          position: 'absolute',
          left: 960 - 150,
          top: NODE_Y - 44,
          width: 300,
          height: 88,
          borderRadius: R.xl,
          background: rgba(M3.d.surfaceHigh, 0.94),
          border: `1px solid ${rgba(M3.d.outlineVariant, 0.8)}`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 14,
          boxShadow: '0 30px 60px -30px rgba(0,0,0,0.9)',
          opacity: seg(f, 30, 56, EASE),
        }}
      >
        <Glyph name="play" size={26} color={M3.d.primary} />
        <span style={{fontSize: 25, fontWeight: 600, color: M3.d.onSurface}}>播放请求</span>
      </div>

      {/* 连线 */}
      <svg
        width={1920}
        height={1080}
        viewBox="0 0 1920 1080"
        style={{position: 'absolute', inset: 0, pointerEvents: 'none'}}
      >
        {CARD_XS.map((cx, i) => (
          <line
            key={cx}
            x1={960}
            y1={NODE_Y + 44}
            x2={cx}
            y2={CARD_Y - CARD_H / 2}
            stroke={rgba(M3.d.outline, 0.75)}
            strokeWidth={2}
            strokeDasharray="9 11"
            opacity={seg(f, 34 + i * 6, 60 + i * 6, EASE)}
          />
        ))}
        {/* 请求包轨迹 */}
        {packet.show ? (
          <line
            x1={960}
            y1={NODE_Y + 44}
            x2={packetPos.x}
            y2={packetPos.y}
            stroke={M3.d.primary}
            strokeWidth={3}
            strokeLinecap="round"
            opacity={0.85}
          />
        ) : null}
      </svg>

      {/* 请求包 */}
      {packet.show ? (
        <div
          style={{
            position: 'absolute',
            left: packetPos.x - 13,
            top: packetPos.y - 13,
            width: 26,
            height: 26,
            borderRadius: 999,
            background: M3.d.primary,
            boxShadow: `0 0 26px 6px ${rgba(M3.d.primary, 0.75)}`,
          }}
        />
      ) : null}

      {/* 三张音源卡 */}
      {SOURCES.map((s, i) => {
        const fail = i < 2 && results[i] > 0.5;
        const ok = i === 2 && results[i] > 0.5;
        const probing = tries[i] > 0 && tries[i] < 1;
        const t = TONE[s.tone];
        return (
          <div
            key={s.name}
            style={{
              position: 'absolute',
              left: CARD_XS[i] - CARD_W / 2,
              top: CARD_Y - CARD_H / 2,
              width: CARD_W,
              height: CARD_H,
              borderRadius: R.xl,
              background: ok
                ? rgba(M3.d.secondaryContainer, 0.5)
                : fail
                  ? rgba(M3.d.errorContainer, 0.34)
                  : rgba(M3.d.surfaceHigh, 0.92),
              border: `1.5px solid ${
                ok ? rgba(M3.d.secondary, 0.6) : fail ? rgba(M3.d.error, 0.5) : rgba(M3.d.outlineVariant, 0.7)
              }`,
              boxShadow: probing
                ? `0 0 0 3px ${rgba(t.fg, 0.3)}, 0 40px 80px -40px rgba(0,0,0,0.9)`
                : '0 40px 80px -40px rgba(0,0,0,0.9)',
              padding: 26,
              boxSizing: 'border-box',
              display: 'flex',
              flexDirection: 'column',
              opacity: cardsIn[i],
              transform: `translateY(${(1 - cardsIn[i]) * 34}px)`,
              transition: 'none',
            }}
          >
            <div style={{display: 'flex', alignItems: 'center', gap: 14}}>
              <Tonal size={56} tone={s.tone}>
                <Glyph name="cube" size={28} />
              </Tonal>
              <div>
                <div style={{fontSize: 28, fontWeight: 700, color: M3.d.onSurface}}>{s.name}</div>
                <div style={{fontSize: 18, color: M3.d.onSurfaceVariant, marginTop: 2}}>{s.meta}</div>
              </div>
            </div>
            <div style={{marginTop: 'auto', display: 'flex', alignItems: 'center', gap: 12, height: 34}}>
              {probing ? (
                <>
                  <WavyProgress progress={1} width={110} height={16} phase={f * 0.34} />
                  <span style={{fontSize: 20, color: M3.d.onSurfaceVariant}}>请求中…</span>
                </>
              ) : ok ? (
                <>
                  <StatusDot tone="ok" size={13} />
                  <span style={{fontSize: 22, fontWeight: 700, color: M3.d.secondary}}>命中 · 切换成功</span>
                </>
              ) : fail ? (
                <>
                  <Glyph name="x" size={24} color={M3.d.error} strokeWidth={2.8} />
                  <span style={{fontSize: 22, fontWeight: 700, color: M3.d.error}}>请求失败</span>
                </>
              ) : (
                <span style={{fontSize: 20, color: M3.d.outline}}>待命</span>
              )}
            </div>
          </div>
        );
      })}

      {/* 底部状态条 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 880,
          display: 'flex',
          justifyContent: 'center',
          opacity: capIn,
          transform: `translateY(${(1 - capIn) * 18}px)`,
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 26,
            padding: '20px 40px',
            borderRadius: 999,
            background: rgba(M3.d.surfaceHigh, 0.86),
            border: `1px solid ${rgba(M3.d.outlineVariant, 0.7)}`,
          }}
        >
          <div style={{display: 'flex', alignItems: 'center', gap: 11}}>
            <StatusDot tone={ledTone} size={12} />
            <span style={{fontSize: 23, color: M3.d.onSurfaceVariant}}>
              健康状态：
              <span
                style={{
                  fontWeight: 700,
                  color: ledTone === 'ok' ? M3.d.secondary : ledTone === 'warn' ? M3.d.tertiary : M3.d.error,
                }}
              >
                {ledTone === 'ok' ? '正常' : ledTone === 'warn' ? '警告' : '异常'}
              </span>
            </span>
          </div>
          <span style={{width: 1, height: 30, background: rgba(M3.d.onSurface, 0.18)}} />
          <span style={{fontSize: 23, color: M3.d.onSurfaceVariant}}>
            全部失败时<span style={{color: M3.d.onSurface, fontWeight: 600}}>回退旧缓存</span>，界面不开天窗
          </span>
        </div>
      </div>
    </AbsoluteFill>
  );
};

/* ==================================================================== */
/* S06 · 缓存按音源隔离                                                  */
/* ==================================================================== */

const COL_W = 430;
const COL_H = 400;
const COL_Y = 640;
const COL_XS = [300, 960, 1620];

const CACHE_KEYS = ['/song/url', '/lyric', '/album', '/playlist/detail', '/artist', '/search', '/comment'];

export const S06Cache: React.FC = () => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();

  const head = seg(f, 4, 32, EASE);
  const colsIn = [0, 1, 2].map((i) => springySettle(f, fps, 14 + i * 8, {damping: 25, stiffness: 125}));

  // 第 0 段：全部展示；第 1 段：切到 B，A/C 的缓存「冻结保留」
  const switchP = seg(f, 120, 160, EASE_IO);
  const freezeIn = seg(f, 150, 186, EASE);
  const capIn = seg(f, 200, 234, EASE);

  const activeIdx = switchP > 0.5 ? 1 : 0;
  const BARS = 7;

  return (
    <AbsoluteFill style={{fontFamily: FONT}}>
      <div style={{position: 'absolute', left: 0, right: 0, top: 96, textAlign: 'center'}}>
        <Chip
          variant="tonal"
          tone="primary"
          size={21}
          icon={<Glyph name="layers" size={19} />}
          style={{opacity: head, marginBottom: 22}}
        >
          缓存隔离
        </Chip>
        <Head size={68} style={{opacity: head, transform: `translateY(${(1 - head) * 24}px)`}}>
          缓存<span style={{color: M3.d.primary}}>各归各的</span>，切换不互相污染
        </Head>
      </div>

      {/* 切换开关 */}
      <div
        style={{
          position: 'absolute',
          left: 960 - 300,
          top: 352,
          width: 600,
          height: 74,
          borderRadius: 999,
          background: rgba(M3.d.surfaceHigh, 0.92),
          border: `1px solid ${rgba(M3.d.outlineVariant, 0.7)}`,
          display: 'flex',
          alignItems: 'center',
          padding: 7,
          boxSizing: 'border-box',
          gap: 6,
          opacity: seg(f, 60, 92, EASE),
        }}
      >
        {SOURCES.map((s, i) => (
          <div
            key={s.name}
            style={{
              flex: 1,
              height: '100%',
              borderRadius: 999,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 9,
              background: activeIdx === i ? TONE[s.tone].cont : 'transparent',
              color: activeIdx === i ? TONE[s.tone].on : M3.d.onSurfaceVariant,
              fontSize: 23,
              fontWeight: activeIdx === i ? 700 : 500,
              boxSizing: 'border-box',
            }}
          >
            {activeIdx === i ? <StatusDot tone="ok" size={11} /> : null}
            {s.name}
          </div>
        ))}
      </div>

      {/* 三列缓存 */}
      {SOURCES.map((s, i) => {
        const t = TONE[s.tone];
        const frozen = switchP > 0.5 && i !== activeIdx;
        return (
          <div
            key={s.name}
            style={{
              position: 'absolute',
              left: COL_XS[i] - COL_W / 2,
              top: COL_Y - COL_H / 2,
              width: COL_W,
              height: COL_H,
              borderRadius: R.xl,
              background: rgba(M3.d.surfaceHigh, 0.9),
              border: `1.5px solid ${activeIdx === i ? rgba(t.fg, 0.5) : rgba(M3.d.outlineVariant, 0.65)}`,
              boxShadow:
                activeIdx === i
                  ? `0 0 0 3px ${rgba(t.fg, 0.22)}, 0 40px 80px -40px rgba(0,0,0,0.9)`
                  : '0 30px 60px -40px rgba(0,0,0,0.85)',
              padding: 24,
              boxSizing: 'border-box',
              opacity: colsIn[i] * (frozen ? mix(freezeIn, 1, 0.62) : 1),
              transform: `translateY(${(1 - colsIn[i]) * 34}px)`,
            }}
          >
            <div style={{display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20}}>
              <Glyph name="cube" size={24} color={t.fg} />
              <span style={{fontSize: 24, fontWeight: 700, color: M3.d.onSurface}}>{s.name}</span>
              <div style={{marginLeft: 'auto'}}>
                {frozen ? (
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      opacity: freezeIn,
                      padding: '5px 12px',
                      borderRadius: 999,
                      background: rgba(M3.d.onSurface, 0.08),
                    }}
                  >
                    <Glyph name="lock" size={17} color={M3.d.onSurfaceVariant} />
                    <span style={{fontSize: 17, color: M3.d.onSurfaceVariant}}>缓存保留</span>
                  </div>
                ) : (
                  <span style={{fontSize: 18, color: t.fg, fontWeight: 600}}>
                    {activeIdx === i ? '读取中' : '就绪'}
                  </span>
                )}
              </div>
            </div>

            <div style={{display: 'flex', flexDirection: 'column', gap: 13}}>
              {Array.from({length: BARS}).map((_, b) => {
                const seed = (i * 7 + b * 3) % 11;
                const v = 0.42 + seed * 0.05;
                const grow = seg(f, 40 + i * 6 + b * 4, 78 + i * 6 + b * 4, EASE);
                return (
                  <div key={b} style={{display: 'flex', alignItems: 'center', gap: 12}}>
                    <span
                      style={{
                        fontSize: 14,
                        color: M3.d.outline,
                        width: 132,
                        flexShrink: 0,
                        fontFamily: "'Cascadia Mono',Consolas,monospace",
                      }}
                    >
                      {CACHE_KEYS[(i * 3 + b) % CACHE_KEYS.length]}
                    </span>
                    <CacheBar
                      value={v * grow}
                      width={COL_W - 48 - 132 - 12}
                      color={t.fg}
                      height={13}
                    />
                  </div>
                );
              })}
            </div>
          </div>
        );
      })}

      {/* 隔离分界线 */}
      <svg
        width={1920}
        height={1080}
        viewBox="0 0 1920 1080"
        style={{position: 'absolute', inset: 0, pointerEvents: 'none'}}
      >
        {[630, 1290].map((x) => (
          <line
            key={x}
            x1={x}
            y1={COL_Y - COL_H / 2 - 30}
            x2={x}
            y2={COL_Y + COL_H / 2 + 30}
            stroke={rgba(M3.d.onSurface, 0.32)}
            strokeWidth={2}
            strokeDasharray="10 12"
            opacity={seg(f, 96, 130, EASE)}
          />
        ))}
      </svg>

      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 912,
          textAlign: 'center',
          opacity: capIn,
          transform: `translateY(${(1 - capIn) * 18}px)`,
        }}
      >
        <span style={{fontSize: 27, color: M3.d.onSurfaceVariant, letterSpacing: 1}}>
          每个音源独立一份缓存 · <span style={{color: M3.d.primary, fontWeight: 700}}>切换后互不影响</span>
        </span>
      </div>
    </AbsoluteFill>
  );
};
