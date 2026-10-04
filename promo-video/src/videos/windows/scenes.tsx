import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C, FONT, MONO} from '../../theme';
import {enter} from '../../lib/anim';
import {Backdrop} from '../../components/Backdrop';
import {Bullet, Chip, Grad, Headline, Panel, Sub} from '../../components/Bits';
import {LogoMark} from '../../components/LogoMark';
import {DesktopWindow} from '../../components/DesktopWindow';
import {EndCard} from '../../components/EndCard';

/* ---------- 01 · Hook：MSI 安装（288f） ---------- */

export const WinHook: React.FC = () => {
  const frame = useCurrentFrame();
  const p = Math.min(1, Math.max(0, (frame - 44) / 170));
  const done = frame >= 214;
  const status = frame < 110 ? '正在解压文件…' : frame < 190 ? '正在配置 JBR 运行时…' : '✓ 安装完成';
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintA="rgba(96,141,249,0.4)" />
      <AbsoluteFill style={{padding: '110px 120px 0', alignItems: 'center'}}>
        <Headline size={118} start={4} align="center">
          双击安装，<Grad>马上开听</Grad>
        </Headline>
        <Sub size={44} start={20} align="center" style={{marginTop: 20}}>
          MSI 安装包 · 内置 JBR 运行时 · 自动检查更新
        </Sub>

        <DesktopWindow
          title="CPPlayer 1.0.0 安装"
          style={{
            width: 980,
            height: 430,
            marginTop: 74,
            opacity: enter(frame, 32),
            translate: `0px ${(1 - enter(frame, 32)) * 44}px`,
          }}
        >
          <div style={{display: 'flex', gap: 44, padding: '52px 64px'}}>
            <LogoMark size={120} />
            <div style={{flex: 1}}>
              <div style={{fontFamily: FONT, fontSize: 44, fontWeight: 700, color: C.text}}>
                CPPlayer <span style={{fontFamily: MONO, fontSize: 32, color: C.sub}}>1.0.0 (x64)</span>
              </div>
              <div
                style={{
                  height: 16,
                  borderRadius: 8,
                  background: 'rgba(255,255,255,0.09)',
                  marginTop: 36,
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    height: '100%',
                    width: `${p * 100}%`,
                    borderRadius: 8,
                    background: 'linear-gradient(90deg,#4A6CF7,#8B5CF6)',
                  }}
                />
              </div>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  marginTop: 26,
                  fontFamily: MONO,
                  fontSize: 28,
                  color: done ? C.green : C.sub,
                }}
              >
                {status}
                {done ? (
                  <span
                    style={{
                      marginLeft: 'auto',
                      fontFamily: FONT,
                      fontSize: 28,
                      color: C.text,
                      border: `1px solid ${C.border}`,
                      background: 'rgba(122,146,255,0.2)',
                      borderRadius: 10,
                      padding: '10px 34px',
                      opacity: enter(frame, 220, 12),
                    }}
                  >
                    完成
                  </span>
                ) : null}
              </div>
            </div>
          </div>
        </DesktopWindow>

        <div style={{display: 'flex', gap: 32, marginTop: 66}}>
          <Chip size={34} style={{opacity: enter(frame, 236)}}>MSI · Dmg · Deb · APK 全都有</Chip>
          <Chip size={34} style={{opacity: enter(frame, 248)}}>更新检查内置</Chip>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 02 · 原生窗口（312f） ---------- */

const SKELETON_ROWS = [72, 55, 88, 40, 66, 78, 48, 62];

export const WinWindow: React.FC = () => {
  const frame = useCurrentFrame();
  // 0-30 出现居中；60-100 吸附到左半屏
  const snap = enter(frame, 60, 40);
  const left = 400 + (48 - 400) * snap;
  const top = 170 + (118 - 170) * snap;
  const width = 1100 + (912 - 1100) * snap;
  const height = 600 + (846 - 600) * snap;
  const guide = frame >= 92 && frame <= 140 ? (Math.floor(frame / 6) % 2 === 0 ? 0.8 : 0.25) : 0;
  const ghost = enter(frame, 150, 26);
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop grid={false} />
      <AbsoluteFill style={{background: 'radial-gradient(ellipse at 70% 30%, rgba(74,108,247,0.18), transparent 60%)'}} />
      <div
        style={{
          position: 'absolute',
          width: 2,
          left: 960,
          top: 90,
          bottom: 96,
          background: 'repeating-linear-gradient(180deg, #7DA2FF 0 14px, transparent 14px 26px)',
          opacity: guide,
        }}
      />
      <DesktopWindow
        title="CPPlayer"
        style={{position: 'absolute', left, top, width, height}}
      >
        <div style={{display: 'flex', height: '100%'}}>
          <div style={{width: 210, background: 'rgba(255,255,255,0.04)', borderRight: `1px solid ${C.border}`, padding: '26px 22px', display: 'flex', flexDirection: 'column', gap: 18}}>
            {['首页', '曲库', '搜索', '下载', '设置'].map((s, i) => (
              <div
                key={s}
                style={{
                  fontFamily: FONT,
                  fontSize: 26,
                  color: i === 0 ? C.text : C.faint,
                  background: i === 0 ? 'rgba(122,146,255,0.18)' : 'transparent',
                  borderRadius: 10,
                  padding: '10px 16px',
                  opacity: enter(frame, 20 + i * 6),
                }}
              >
                {s}
              </div>
            ))}
          </div>
          <div style={{flex: 1, padding: '28px 34px'}}>
            <div style={{fontFamily: FONT, fontSize: 34, fontWeight: 700, color: C.text, opacity: enter(frame, 26)}}>
              今天想听点什么？
            </div>
            <div style={{display: 'flex', flexDirection: 'column', gap: 16, marginTop: 30}}>
              {SKELETON_ROWS.map((w, i) => (
                <div
                  key={i}
                  style={{
                    height: 22,
                    width: `${w}%`,
                    borderRadius: 8,
                    background: 'rgba(255,255,255,0.08)',
                    opacity: enter(frame, 36 + i * 7),
                  }}
                />
              ))}
            </div>
          </div>
        </div>
      </DesktopWindow>

      {/* 右半屏的「别的应用」幽灵窗 */}
      <div
        style={{
          position: 'absolute',
          left: 972,
          top: 118,
          width: 900,
          height: 846,
          borderRadius: 14,
          border: `1px dashed rgba(255,255,255,0.35)`,
          background: 'rgba(255,255,255,0.04)',
          opacity: ghost * 0.6,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontFamily: FONT,
          fontSize: 30,
          color: C.sub,
        }}
      >
        别的应用
      </div>

      <div style={{position: 'absolute', left: 120, right: 120, bottom: 130, display: 'flex', gap: 26, flexWrap: 'wrap'}}>
        <Chip size={32} style={{opacity: enter(frame, 210)}}>Aero Snap 贴边吸附</Chip>
        <Chip size={32} style={{opacity: enter(frame, 222)}}>原生阴影 / 缩放 / 最大化</Chip>
        <Chip size={32} style={{opacity: enter(frame, 234)}}>
          <span style={{fontFamily: MONO, background: 'rgba(255,255,255,0.14)', borderRadius: 8, padding: '2px 14px'}}>Esc</span>
          返回上一级
        </Chip>
        <Chip size={32} style={{opacity: enter(frame, 246)}}>标题栏直达返回</Chip>
      </div>

      {/* 任务栏 */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 0,
          height: 84,
          background: 'rgba(8,12,24,0.9)',
          borderTop: `1px solid ${C.border}`,
          display: 'flex',
          alignItems: 'center',
          padding: '0 34px',
        }}
      >
        <div style={{display: 'flex', gap: 10}}>
          {[0, 1, 2, 3].map((i) => (
            <span key={i} style={{width: 13, height: 13, background: i === 0 ? '#7DA2FF' : 'rgba(255,255,255,0.25)', borderRadius: 3}} />
          ))}
        </div>
        <div style={{flex: 1}} />
        <span style={{fontFamily: FONT, fontSize: 26, color: C.sub}}>21:32</span>
        <span style={{fontFamily: FONT, fontSize: 26, color: C.faint, marginLeft: 24}}>2026/10/04</span>
      </div>
    </AbsoluteFill>
  );
};

/* ---------- 03 · 任务栏媒体控制（300f） ---------- */

const PlayGlyph: React.FC<{readonly size?: number; readonly color?: string}> = ({size = 26, color = '#fff'}) => (
  <span
    style={{
      width: 0,
      height: 0,
      borderTop: `${size * 0.62}px solid transparent`,
      borderBottom: `${size * 0.62}px solid transparent`,
      borderLeft: `${size}px solid ${color}`,
      display: 'block',
      marginLeft: size * 0.12,
    }}
  />
);

const PauseGlyph: React.FC<{readonly size?: number; readonly color?: string}> = ({size = 26, color = '#fff'}) => (
  <span style={{display: 'flex', gap: size * 0.24}}>
    <span style={{width: size * 0.26, height: size * 1.2, background: color, borderRadius: 2}} />
    <span style={{width: size * 0.26, height: size * 1.2, background: color, borderRadius: 2}} />
  </span>
);

const TransButton: React.FC<{
  readonly children: React.ReactNode;
  readonly big?: boolean;
  readonly start?: number;
}> = ({children, big, start = 0}) => {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        width: big ? 96 : 72,
        height: big ? 96 : 72,
        borderRadius: 999,
        background: big ? 'linear-gradient(135deg,#4A6CF7,#8B5CF6)' : 'rgba(255,255,255,0.09)',
        border: `1px solid ${C.border}`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        boxShadow: big ? '0 16px 40px rgba(74,108,247,0.4)' : 'none',
        opacity: enter(frame, start),
        scale: `${0.85 + enter(frame, start) * 0.15}`,
      }}
    >
      {children}
    </div>
  );
};

export const WinMedia: React.FC = () => {
  const frame = useCurrentFrame();
  const playing = frame < 150;
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintA="rgba(96,141,249,0.4)" tintB="rgba(52,211,153,0.18)" />
      <AbsoluteFill style={{padding: '104px 120px 0'}}>
        <Headline size={104} start={0}>
          任务栏<Grad>直接控制播放</Grad>
        </Headline>
        <Sub size={42} start={16} style={{marginTop: 16}}>
          系统媒体控件 · 媒体键 · 全局快捷键，切歌不必打开主窗口
        </Sub>

        <div style={{display: 'flex', alignItems: 'center', gap: 70, marginTop: 76, justifyContent: 'center'}}>
          <div
            style={{
              width: 300,
              height: 300,
              borderRadius: 28,
              background: 'linear-gradient(135deg,#4A6CF7 0%,#8B5CF6 60%,#22D3EE 130%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 30px 80px rgba(74,108,247,0.35)',
              opacity: enter(frame, 24),
              scale: `${0.9 + enter(frame, 24) * 0.1}`,
            }}
          >
            <span style={{fontFamily: FONT, fontSize: 96, color: 'rgba(255,255,255,0.9)'}}>♪</span>
          </div>
          <div>
            <div style={{fontFamily: FONT, fontSize: 52, fontWeight: 700, color: C.text, opacity: enter(frame, 38)}}>
              Night Drive
            </div>
            <div style={{fontFamily: FONT, fontSize: 34, color: C.sub, marginTop: 10, opacity: enter(frame, 48)}}>
              Aurora Keys · 无损音质
            </div>
            <div style={{display: 'flex', gap: 26, alignItems: 'flex-end', height: 60, marginTop: 34}}>
              {[0, 1, 2, 3, 4, 5, 6].map((i) => (
                <span
                  key={i}
                  style={{
                    width: 14,
                    borderRadius: 7,
                    background: 'linear-gradient(180deg,#8B5CF6,#4A6CF7)',
                    height: playing
                      ? 22 + Math.abs(Math.sin(frame / 8 + i * 0.9)) * 38
                      : 30,
                    opacity: enter(frame, 56 + i * 4),
                  }}
                />
              ))}
            </div>
          </div>
        </div>

        <div style={{display: 'flex', gap: 30, justifyContent: 'center', marginTop: 60, alignItems: 'center'}}>
          <TransButton start={70}>
            <span style={{transform: 'scaleX(-1)', display: 'block'}}>
              <PlayGlyph size={22} color={C.text} />
            </span>
          </TransButton>
          <TransButton big start={80}>
            {playing ? <PlayGlyph size={30} /> : <PauseGlyph size={30} />}
          </TransButton>
          <TransButton start={70}>
            <PlayGlyph size={22} color={C.text} />
          </TransButton>
        </div>

        <div style={{display: 'flex', gap: 60, marginTop: 52, paddingLeft: 40, justifyContent: 'flex-start'}}>
          <Bullet start={210} accent={C.cyan}>任务栏媒体浮层 · 媒体键直控</Bullet>
          <Bullet start={224} accent={C.purple}>全局快捷键 · 后台常驻播放</Bullet>
        </div>
      </AbsoluteFill>

      {/* 任务栏 + 媒体浮层 */}
      <div
        style={{
          position: 'absolute',
          right: 150,
          bottom: 240,
          width: 520,
          borderRadius: 18,
          border: `1px solid ${C.border}`,
          background: 'rgba(16,22,40,0.96)',
          boxShadow: '0 30px 70px rgba(0,0,0,0.6)',
          padding: '26px 30px',
          display: 'flex',
          gap: 22,
          alignItems: 'center',
          opacity: enter(frame, 120, 16),
          translate: `0px ${(1 - enter(frame, 120, 16)) * 30}px`,
        }}
      >
        <div style={{width: 92, height: 92, borderRadius: 14, background: 'linear-gradient(135deg,#4A6CF7,#8B5CF6)', flexShrink: 0}} />
        <div style={{flex: 1}}>
          <div style={{fontFamily: FONT, fontSize: 28, color: C.text, fontWeight: 600}}>Night Drive</div>
          <div style={{fontFamily: FONT, fontSize: 24, color: C.sub, marginTop: 4}}>Aurora Keys</div>
          <div style={{display: 'flex', gap: 18, marginTop: 14, alignItems: 'center'}}>
            {playing ? <PauseGlyph size={13} color={C.sub} /> : <PlayGlyph size={13} color={C.sub} />}
            <div style={{flex: 1, height: 6, borderRadius: 3, background: 'rgba(255,255,255,0.12)'}}>
              <div style={{width: `${38 + (frame % 90) * 0.6}%`, height: '100%', borderRadius: 3, background: 'linear-gradient(90deg,#4A6CF7,#8B5CF6)'}} />
            </div>
            <PlayGlyph size={13} color={C.sub} />
          </div>
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 0,
          height: 84,
          background: 'rgba(8,12,24,0.94)',
          borderTop: `1px solid ${C.border}`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 22,
        }}
      >
        {['首页', '曲库', '搜索', '下载'].map((s) => (
          <span key={s} style={{fontFamily: FONT, fontSize: 24, color: C.faint}}>{s}</span>
        ))}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '10px 22px',
            borderRadius: 12,
            background: 'rgba(122,146,255,0.2)',
            border: '1px solid rgba(122,146,255,0.5)',
            opacity: enter(frame, 130),
          }}
        >
          <span style={{width: 26, height: 26, borderRadius: 7, background: 'linear-gradient(135deg,#4A6CF7,#8B5CF6)'}} />
          <span style={{fontFamily: FONT, fontSize: 24, color: C.text}}>CPPlayer</span>
          {playing ? <PauseGlyph size={11} color={C.text} /> : <PlayGlyph size={11} color={C.text} />}
        </div>
        <span style={{fontFamily: FONT, fontSize: 24, color: C.faint}}>设置</span>
      </div>
    </AbsoluteFill>
  );
};

/* ---------- 04 · 音源管理（288f） ---------- */

const SOURCE_ROWS: ReadonlyArray<{
  readonly name: string;
  readonly state: string;
  readonly color: string;
  readonly dot: string;
  readonly start: number;
}> = [
  {name: '音源 A', state: '✓ 已激活', color: C.green, dot: C.green, start: 78},
  {name: '音源 B', state: 'WARNING · 限流', color: C.amber, dot: C.amber, start: 118},
  {name: '音源 C', state: '待命', color: C.faint, dot: C.faint, start: 158},
];

export const WinSource: React.FC = () => {
  const frame = useCurrentFrame();
  const fly = enter(frame, 30, 26);
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintB="rgba(52,211,153,0.16)" />
      <AbsoluteFill style={{padding: '104px 120px 0'}}>
        <Headline size={104} start={0}>
          音源自己选，<Grad>坏了能兜底</Grad>
        </Headline>
        <Sub size={42} start={16} style={{marginTop: 16}}>
          .cpm 模块导入即用 · 多 Provider 容灾 · 缓存按音源与账号隔离
        </Sub>

        <div style={{display: 'flex', gap: 56, marginTop: 70}}>
          <Panel style={{width: 820, padding: '36px 44px'}}>
            <div style={{display: 'flex', alignItems: 'center'}}>
              <span style={{fontFamily: FONT, fontSize: 38, fontWeight: 700, color: C.text}}>音源管理</span>
              <span style={{fontFamily: MONO, fontSize: 24, color: C.faint, marginLeft: 'auto'}}>设置 → 音源管理</span>
            </div>
            <div
              style={{
                marginTop: 26,
                display: 'inline-flex',
                gap: 14,
                alignItems: 'center',
                border: `1px dashed rgba(122,146,255,0.6)`,
                borderRadius: 14,
                padding: '18px 30px',
                fontFamily: MONO,
                fontSize: 28,
                color: C.cyan,
                opacity: fly,
                translate: `${(1 - fly) * -120}px 0px`,
                background: 'rgba(34,211,238,0.07)',
              }}
            >
              ⬆ package.cpm · 拖入导入
            </div>
            <div style={{display: 'flex', flexDirection: 'column', gap: 16, marginTop: 28}}>
              {SOURCE_ROWS.map((row) => {
                const p = enter(frame, row.start);
                return (
                  <div
                    key={row.name}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 18,
                      padding: '22px 28px',
                      borderRadius: 14,
                      background: 'rgba(255,255,255,0.05)',
                      border: `1px solid ${C.border}`,
                      opacity: p,
                      translate: `0px ${(1 - p) * 20}px`,
                    }}
                  >
                    <span style={{width: 14, height: 14, borderRadius: 999, background: row.dot, boxShadow: `0 0 14px ${row.dot}`}} />
                    <span style={{fontFamily: FONT, fontSize: 34, color: C.text, fontWeight: 600}}>{row.name}</span>
                    <span style={{fontFamily: MONO, fontSize: 26, color: row.color, marginLeft: 'auto'}}>{row.state}</span>
                  </div>
                );
              })}
            </div>
          </Panel>

          <Panel style={{flex: 1, padding: '36px 44px'}}>
            <div style={{fontFamily: FONT, fontSize: 38, fontWeight: 700, color: C.text}}>一次请求的兜底链</div>
            <div style={{display: 'flex', flexDirection: 'column', marginTop: 30, gap: 0}}>
              {[
                {t: '音源 A 请求失败', c: C.red, start: 60},
                {t: '自动切换 → 音源 B 成功', c: C.green, start: 120},
                {t: '全失败 → 过期缓存兜底', c: C.amber, start: 180},
              ].map((step, i) => (
                <div key={step.t} style={{display: 'flex', gap: 20}}>
                  <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center'}}>
                    <span
                      style={{
                        width: 16,
                        height: 16,
                        borderRadius: 999,
                        background: step.c,
                        boxShadow: `0 0 14px ${step.c}`,
                        opacity: enter(frame, step.start),
                      }}
                    />
                    {i < 2 ? (
                      <span
                        style={{
                          width: 3,
                          flex: 1,
                          minHeight: 34,
                          background: 'rgba(255,255,255,0.14)',
                          transformOrigin: 'top',
                          scale: `1 ${enter(frame, step.start + 14, 20)}`,
                        }}
                      />
                    ) : null}
                  </div>
                  <span
                    style={{
                      fontFamily: FONT,
                      fontSize: 32,
                      color: C.text,
                      opacity: enter(frame, step.start),
                      paddingBottom: i < 2 ? 34 : 0,
                    }}
                  >
                    {step.t}
                  </span>
                </div>
              ))}
            </div>
            <div style={{fontFamily: FONT, fontSize: 28, color: C.sub, marginTop: 26, opacity: enter(frame, 210)}}>
              界面顶部健康状态常驻 · 详情见「设置 → 诊断」
            </div>
          </Panel>
        </div>

        <div style={{display: 'flex', gap: 60, justifyContent: 'center', marginTop: 52}}>
          <Bullet start={226} accent={C.cyan}>导入即激活首个 Provider</Bullet>
          <Bullet start={240} accent={C.purple}>多账号数据互相隔离</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 05 · 下载与本地音乐（258f） ---------- */

const DL_ROWS: ReadonlyArray<{readonly title: string; readonly size: string; readonly start: number}> = [
  {title: 'Night Drive — Aurora Keys', size: '38.2 MB', start: 40},
  {title: 'City Rain — Mochi', size: '31.5 MB', start: 84},
  {title: '深夜公交 — 南屿', size: '42.8 MB', start: 128},
];

export const WinDaily: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintA="rgba(96,141,249,0.38)" tintB="rgba(139,92,246,0.3)" />
      <AbsoluteFill style={{padding: '104px 120px 0'}}>
        <Headline size={104} start={0}>
          下载、本地音乐，<Grad>都安排上</Grad>
        </Headline>
        <Sub size={42} start={16} style={{marginTop: 16}}>
          离线下载管理 · 本地媒体扫描 · 音质随心选
        </Sub>

        <div style={{display: 'flex', gap: 40, marginTop: 66}}>
          <Panel style={{width: 560, padding: '34px 38px', opacity: enter(frame, 10), translate: `0px ${(1 - enter(frame, 10)) * 28}px`}}>
            <div style={{fontFamily: FONT, fontSize: 36, fontWeight: 700, color: C.text}}>离线下载</div>
            <div style={{display: 'flex', flexDirection: 'column', gap: 22, marginTop: 26}}>
              {DL_ROWS.map((row, i) => {
                const p = Math.min(1, Math.max(0, (frame - row.start) / 80));
                const done = p >= 1;
                return (
                  <div key={i}>
                    <div style={{display: 'flex', fontFamily: FONT, fontSize: 27, color: C.text}}>
                      <span style={{whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{row.title}</span>
                      <span style={{marginLeft: 'auto', fontFamily: MONO, fontSize: 24, color: done ? C.green : C.sub, paddingLeft: 14}}>
                        {done ? '✓' : row.size}
                      </span>
                    </div>
                    <div style={{height: 10, borderRadius: 5, background: 'rgba(255,255,255,0.1)', marginTop: 12}}>
                      <div style={{width: `${p * 100}%`, height: '100%', borderRadius: 5, background: 'linear-gradient(90deg,#4A6CF7,#8B5CF6)'}} />
                    </div>
                  </div>
                );
              })}
            </div>
          </Panel>

          <Panel style={{width: 560, padding: '34px 38px', opacity: enter(frame, 70), translate: `0px ${(1 - enter(frame, 70)) * 28}px`}}>
            <div style={{fontFamily: FONT, fontSize: 36, fontWeight: 700, color: C.text}}>本地媒体扫描</div>
            <div style={{display: 'flex', alignItems: 'baseline', gap: 16, marginTop: 34}}>
              <span style={{fontFamily: MONO, fontSize: 76, fontWeight: 700, color: C.cyan}}>
                {Math.round(enter(frame, 100, 50) * 1024).toLocaleString('en-US')}
              </span>
              <span style={{fontFamily: FONT, fontSize: 32, color: C.sub}}>首已入库</span>
            </div>
            <div style={{fontFamily: MONO, fontSize: 26, color: C.faint, marginTop: 22}}>~/Music · 自动扫描</div>
            <div style={{display: 'flex', gap: 14, marginTop: 30}}>
              {['专辑', '歌手', '年份'].map((tag, i) => (
                <Chip key={tag} size={26} style={{opacity: enter(frame, 150 + i * 10)}}>{tag}</Chip>
              ))}
            </div>
          </Panel>

          <Panel style={{flex: 1, padding: '34px 38px', opacity: enter(frame, 130), translate: `0px ${(1 - enter(frame, 130)) * 28}px`}}>
            <div style={{fontFamily: FONT, fontSize: 36, fontWeight: 700, color: C.text}}>音质随心选</div>
            <div style={{display: 'flex', flexDirection: 'column', gap: 18, marginTop: 28}}>
              {[
                {t: '标准', d: '128 kbps'},
                {t: '极高', d: '320 kbps'},
                {t: '无损', d: 'FLAC', hot: true},
              ].map((q, i) => (
                <div
                  key={q.t}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: '20px 26px',
                    borderRadius: 14,
                    border: `1px solid ${q.hot ? 'rgba(122,146,255,0.6)' : C.border}`,
                    background: q.hot && frame >= 190 ? 'rgba(122,146,255,0.16)' : 'rgba(255,255,255,0.04)',
                    opacity: enter(frame, 160 + i * 14),
                  }}
                >
                  <span style={{fontFamily: FONT, fontSize: 30, color: C.text, fontWeight: 600}}>{q.t}</span>
                  <span style={{fontFamily: MONO, fontSize: 24, color: C.sub, marginLeft: 'auto'}}>{q.d}</span>
                </div>
              ))}
            </div>
          </Panel>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 06 · 收尾（144f） ---------- */

export const WinEnd: React.FC = () => (
  <EndCard
    tagline="少折腾，多听歌"
    note="同一播放内核 · 同一套音源插件"
    chips={['Windows', 'macOS', 'Linux', 'Android']}
    start={8}
  />
);
