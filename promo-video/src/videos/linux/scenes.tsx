import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C, FONT, MONO} from '../../theme';
import {enter} from '../../lib/anim';
import {Backdrop} from '../../components/Backdrop';
import {Bullet, Chip, Grad, Headline, Panel, Sub} from '../../components/Bits';
import {LogoMark} from '../../components/LogoMark';
import {Terminal} from '../../components/Terminal';
import {Typewriter} from '../../components/Typewriter';
import {PhoneFrame} from '../../components/PhoneFrame';
import {RealShot} from '../../components/RealShot';
import {EndCard} from '../../components/EndCard';

/* ---------- 01 · Hook：终端安装（300f） ---------- */

const HOOK_LINES = {
  wget: '$ wget https://dl.cpplayer.dev/cpplayer_1.0.0_amd64.deb',
  sudo: '$ sudo dpkg -i cpplayer_1.0.0_amd64.deb',
  run: '$ cpplayer &',
};

export const LinuxHook: React.FC = () => {
  const frame = useCurrentFrame();
  const dl = Math.min(1, Math.max(0, (frame - 100) / 36));
  const blocks = Math.round(dl * 24);
  const hp = enter(frame, 6);
  const tp = enter(frame, 30);
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '96px 120px 0'}}>
        <div style={{display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between'}}>
          <div>
            <Headline size={118} start={6}>
              你的音乐栈，<Grad>你说了算</Grad>
            </Headline>
            <Sub size={44} start={20} style={{marginTop: 22}}>
              CPPlayer · 跨平台音乐播放器 · Kotlin Multiplatform
            </Sub>
          </div>
          <div style={{opacity: enter(frame, 16), scale: `${0.9 + enter(frame, 16) * 0.1}`}}>
            <LogoMark size={148} />
          </div>
        </div>
        <Terminal
          title="fish — archlinux"
          style={{width: 1240, marginTop: 52, opacity: tp, translate: `0px ${(1 - tp) * 44}px`}}
        >
          <Typewriter text={HOOK_LINES.wget} start={40} cpf={1.0} />
          <div style={{color: C.sub, minHeight: '1.72em'}}>
            {frame >= 100
              ? `  ${'█'.repeat(blocks)}${'░'.repeat(24 - blocks)}  ${(86.4 * dl).toFixed(1)} MB · 100%`
              : ' '}
          </div>
          <Typewriter text={HOOK_LINES.sudo} start={144} cpf={1.2} />
          <div style={{color: C.green, minHeight: '1.72em'}}>
            {frame >= 188 ? '✓ 已安装 cpplayer 1.0.0 (amd64) · 内置 JBR 运行时' : ' '}
          </div>
          <Typewriter text={HOOK_LINES.run} start={216} cpf={1.6} />
          <div style={{color: C.cyan, minHeight: '1.72em'}}>
            {frame >= 240 ? '[+] 已启动 · 原生窗口 · 等待音源模块…' : ' '}
          </div>
        </Terminal>
      </AbsoluteFill>
      <div
        style={{
          position: 'absolute',
          right: 120,
          bottom: 72,
          fontFamily: MONO,
          fontSize: 30,
          color: C.faint,
          opacity: hp,
        }}
      >
        deb · dmg · msi · apk
      </div>
    </AbsoluteFill>
  );
};

/* ---------- 02 · 音源即插件（300f） ---------- */

const Box: React.FC<{
  readonly title: string;
  readonly caption: string;
  readonly start: number;
  readonly width?: number;
  readonly children?: React.ReactNode;
  readonly glow?: boolean;
}> = ({title, caption, start, width = 430, children, glow}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start);
  return (
    <Panel
      style={{
        width,
        padding: '30px 34px',
        opacity: p,
        translate: `0px ${(1 - p) * 30}px`,
        borderColor: glow ? 'rgba(122,146,255,0.55)' : C.border,
        boxShadow: glow
          ? '0 0 0 4px rgba(122,146,255,0.18), 0 24px 60px rgba(0,0,0,0.4)'
          : '0 24px 60px rgba(0,0,0,0.35)',
        transition: 'none',
      }}
    >
      <div style={{fontFamily: FONT, fontSize: 40, fontWeight: 700, color: C.text}}>
        {title}
      </div>
      <div style={{fontFamily: MONO, fontSize: 24, color: C.sub, marginTop: 10}}>
        {caption}
      </div>
      {children}
    </Panel>
  );
};

const HArrow: React.FC<{readonly start: number}> = ({start}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start, 16);
  return (
    <div style={{display: 'flex', alignItems: 'center', width: 92, flexShrink: 0}}>
      <div
        style={{
          height: 5,
          flex: 1,
          borderRadius: 3,
          background: 'rgba(255,255,255,0.12)',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            position: 'absolute',
            inset: 0,
            width: `${p * 100}%`,
            background: 'linear-gradient(90deg,#4A6CF7,#8B5CF6)',
          }}
        />
      </div>
      <span style={{color: C.purple, fontSize: 34, marginLeft: 4, opacity: p, lineHeight: 1}}>›</span>
    </div>
  );
};

const PROVIDER_ROWS: ReadonlyArray<{readonly name: string; readonly state: string; readonly color: string}> = [
  {name: '音源 A', state: '✓ 已激活', color: C.green},
  {name: '音源 B', state: '待命', color: C.faint},
  {name: '音源 C', state: '待命', color: C.faint},
];

export const LinuxArch: React.FC = () => {
  const frame = useCurrentFrame();
  const activated = frame >= 232;
  const chipP = enter(frame, 196, 22);
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '110px 120px 0'}}>
        <Headline size={104} start={0}>
          音源即<Grad>插件</Grad>
        </Headline>
        <Sub size={42} start={18} style={{marginTop: 18}}>
          导入 .cpm 音源模块即可使用 —— CPPlayer 自身不内置任何数据源
        </Sub>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 24,
            marginTop: 84,
          }}
        >
          <Box title="Compose UI" caption="app 模块 · 跨平台界面" start={10} />
          <HArrow start={40} />
          <Box title="MusicBackend" caption="统一入口 · 状态机 / 缓存 / 播放" start={55} />
          <HArrow start={85} />
          <Box title="Provider 容器" caption="可插拔音源" start={100} glow={activated}>
            <div style={{display: 'flex', flexDirection: 'column', gap: 12, marginTop: 20}}>
              {PROVIDER_ROWS.map((row, i) => {
                const p = enter(frame, 120 + i * 18);
                const isA = i === 0;
                return (
                  <div
                    key={row.name}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 14,
                      padding: '12px 18px',
                      borderRadius: 12,
                      background: isA && activated ? 'rgba(52,211,153,0.12)' : 'rgba(255,255,255,0.05)',
                      border: `1px solid ${isA && activated ? 'rgba(52,211,153,0.4)' : 'rgba(255,255,255,0.07)'}`,
                      opacity: p,
                      translate: `0px ${(1 - p) * 16}px`,
                      fontFamily: FONT,
                      fontSize: 28,
                      color: C.text,
                    }}
                  >
                    <span
                      style={{
                        width: 12,
                        height: 12,
                        borderRadius: 999,
                        background: isA && activated ? C.green : C.faint,
                        boxShadow: isA && activated ? `0 0 14px ${C.green}` : 'none',
                      }}
                    />
                    {row.name}
                    <span
                      style={{
                        marginLeft: 'auto',
                        fontFamily: MONO,
                        fontSize: 22,
                        color: isA && activated ? C.green : C.faint,
                      }}
                    >
                      {isA && activated ? '✓ 已激活' : row.state}
                    </span>
                  </div>
                );
              })}
            </div>
          </Box>
        </div>

        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            marginTop: 46,
            opacity: chipP,
            translate: `0px ${(1 - chipP) * 46}px`,
          }}
        >
          <Chip accent="rgba(34,211,238,0.12)" size={34}>
            <span style={{fontFamily: MONO, color: C.cyan}}>package.cpm</span>
            <span style={{color: C.sub}}>音源模块 · 导入即激活首个 Provider</span>
          </Chip>
        </div>

        <div style={{display: 'flex', gap: 60, justifyContent: 'center', marginTop: 54}}>
          <Bullet start={248} accent={C.cyan}>热插拔 · 随时切换音源</Bullet>
          <Bullet start={260} accent={C.purple}>缓存按音源隔离，互不污染</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 03 · 多 Provider 容灾（270f） ---------- */

const FAILOVER_CARDS: ReadonlyArray<{readonly name: string; readonly caption: string}> = [
  {name: '音源 A', caption: '首选'},
  {name: '音源 B', caption: '备用'},
  {name: '音源 C', caption: '备用'},
];

export const LinuxFailover: React.FC = () => {
  const frame = useCurrentFrame();
  const aErr = enter(frame, 62, 12);
  const bOk = enter(frame, 124, 12);
  // 请求胶囊两段飞行：画布上方 → 卡 A 上方 → 卡 B 上方
  const leg1 = enter(frame, 18, 34);
  const leg2 = enter(frame, 84, 32);
  const inLeg1 = leg1 < 1;
  const pillX = inLeg1 ? 760 + (210 - 760) * leg1 : 210 + (750 - 210) * leg2;
  const pillY = inLeg1 ? 220 + (318 - 220) * leg1 : 318;
  const showPill = frame >= 18 && frame <= 150;
  const pillOpacity = frame >= 130 ? Math.max(0, 1 - (frame - 130) / 20) : 1;
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '110px 120px 0'}}>
        <Headline size={104} start={0}>
          一个源挂了，<Grad>自动切下一个</Grad>
        </Headline>
        <Sub size={42} start={16} style={{marginTop: 18}}>
          多 Provider 容灾 · 失败自动回退，界面尽量有内容可看
        </Sub>

        <div style={{position: 'relative', display: 'flex', gap: 40, justifyContent: 'center', marginTop: 96}}>
          {FAILOVER_CARDS.map((card, i) => {
            const isA = i === 0;
            const isB = i === 1;
            const p = enter(frame, 14 + i * 14);
            const state = isA && aErr > 0.5 ? 'ERROR' : isB && bOk > 0.5 ? 'OK' : 'IDLE';
            const ring =
              state === 'ERROR'
                ? {border: `1px solid rgba(248,113,113,${0.3 + aErr * 0.5})`, background: `rgba(248,113,113,${0.06 * aErr})`}
                : state === 'OK'
                  ? {border: `1px solid rgba(52,211,153,${0.3 + bOk * 0.5})`, background: `rgba(52,211,153,${0.07 * bOk})`}
                  : {border: `1px solid ${C.border}`, background: C.panel};
            return (
              <Panel
                key={card.name}
                style={{
                  width: 500,
                  padding: '34px 38px',
                  opacity: p,
                  translate: `0px ${(1 - p) * 30}px`,
                  ...ring,
                }}
              >
                <div style={{display: 'flex', alignItems: 'center'}}>
                  <span style={{fontFamily: FONT, fontSize: 46, fontWeight: 700, color: C.text}}>
                    {card.name}
                  </span>
                  <span style={{fontFamily: MONO, fontSize: 24, color: C.faint, marginLeft: 'auto'}}>
                    {card.caption}
                  </span>
                </div>
                <div style={{fontFamily: MONO, fontSize: 30, marginTop: 18, minHeight: '1.4em'}}>
                  {state === 'ERROR' ? (
                    <span style={{color: C.red, opacity: aErr}}>✗ ERROR · 连接失败</span>
                  ) : state === 'OK' ? (
                    <span style={{color: C.green, opacity: bOk}}>✓ 200 OK · 已接管</span>
                  ) : (
                    <span style={{color: C.faint}}>— 待命</span>
                  )}
                </div>
                <div style={{display: 'flex', gap: 8, alignItems: 'flex-end', height: 44, marginTop: 16}}>
                  {[0, 1, 2, 3, 4, 5, 6].map((b) => (
                    <span
                      key={b}
                      style={{
                        width: 12,
                        borderRadius: 6,
                        background:
                          state === 'ERROR' ? 'rgba(248,113,113,0.5)' : state === 'OK' ? C.green : 'rgba(255,255,255,0.16)',
                        height: 14 + ((b * 29 + (isA ? 11 : 23)) % 30),
                        opacity: state === 'IDLE' ? 0.5 : 0.9,
                      }}
                    />
                  ))}
                </div>
              </Panel>
            );
          })}

          {showPill ? (
            <div
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                translate: `${pillX - 210}px ${pillY - 318}px`,
                opacity: pillOpacity,
              }}
            >
              <div
                style={{
                  fontFamily: MONO,
                  fontSize: 26,
                  color: C.text,
                  background: 'rgba(20,28,50,0.95)',
                  border: '1px solid rgba(122,146,255,0.5)',
                  borderRadius: 12,
                  padding: '12px 22px',
                  whiteSpace: 'nowrap',
                  boxShadow: '0 14px 40px rgba(0,0,0,0.5)',
                }}
              >
                GET /song/url?id=347230
              </div>
            </div>
          ) : null}
        </div>

        <div style={{display: 'flex', gap: 40, alignItems: 'stretch', marginTop: 64}}>
          {/* 真实界面：设置 → 音源管理 */}
          <div style={{flexShrink: 0, opacity: enter(frame, 160), translate: `0px ${(1 - enter(frame, 160)) * 24}px`}}>
            <RealShot
              src="shots/shot-desktop-sources.png"
              width={620}
              height={300}
              radius={14}
              style={{border: `1px solid ${C.border}`, background: '#0D1322', boxShadow: '0 24px 60px rgba(0,0,0,0.35)'}}
            />
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                marginTop: 14,
                fontFamily: FONT,
                fontSize: 24,
                color: C.faint,
                opacity: enter(frame, 190),
              }}
            >
              <span
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: 999,
                  background: C.green,
                  boxShadow: `0 0 12px ${C.green}`,
                }}
              />
              真实界面 · 音源 A 已激活 / 音源 B 待命警告
            </div>
          </div>

          <Panel style={{flex: 1, padding: '26px 46px', display: 'flex', alignItems: 'center', opacity: enter(frame, 176), translate: `0px ${(1 - enter(frame, 176)) * 24}px`}}>
            <span style={{fontFamily: FONT, fontSize: 36, color: C.text, lineHeight: 1.6}}>
              回退顺序：<span style={{color: C.cyan}}>其他 Provider</span> →{' '}
              <span style={{color: C.amber}}>过期缓存兜底</span> → 界面照样有内容
            </span>
          </Panel>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 04 · 读透缓存（270f） ---------- */

const fmt = (n: number): string => n.toLocaleString('en-US');

const StatRow: React.FC<{
  readonly label: string;
  readonly value: number;
  readonly start: number;
  readonly color: string;
}> = ({label, value, start, color}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start, 36);
  return (
    <div style={{display: 'flex', alignItems: 'baseline', gap: 20, opacity: enter(frame, start, 16)}}>
      <span style={{fontFamily: FONT, fontSize: 32, color: C.sub, width: 300}}>{label}</span>
      <span style={{fontFamily: MONO, fontSize: 62, fontWeight: 700, color}}>
        {fmt(Math.round(p * value))}
      </span>
    </div>
  );
};

export const LinuxCache: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '110px 120px 0'}}>
        <Headline size={100} start={0}>
          读透缓存，<Grad>等待和流量都省了</Grad>
        </Headline>
        <Sub size={42} start={16} style={{marginTop: 16}}>
          命中直接返回 · 过期自动回源刷新 · 失败还能吃旧缓存
        </Sub>

        <div style={{display: 'flex', gap: 60, marginTop: 70}}>
          <div style={{display: 'flex', flexDirection: 'column', gap: 20, width: 860}}>
            <Panel style={{padding: '26px 34px', opacity: enter(frame, 10), translate: `0px ${(1 - enter(frame, 10)) * 22}px`}}>
              <span style={{fontFamily: FONT, fontSize: 36, color: C.text}}>① 请求 · 歌单详情 / 搜索 / 歌词</span>
            </Panel>
            <Panel style={{padding: '26px 34px', opacity: enter(frame, 50), translate: `0px ${(1 - enter(frame, 50)) * 22}px`}}>
              <span style={{fontFamily: FONT, fontSize: 36, color: C.text}}>② 缓存命中？未过期 → 直接返回</span>
              <span style={{fontFamily: MONO, fontSize: 28, color: C.green, marginLeft: 16}}>0ms 网络</span>
            </Panel>
            <Panel style={{padding: '26px 34px', opacity: enter(frame, 92), translate: `0px ${(1 - enter(frame, 92)) * 22}px`}}>
              <span style={{fontFamily: FONT, fontSize: 36, color: C.text}}>③ 未命中 → 回源 → 写回缓存</span>
              <span style={{fontFamily: MONO, fontSize: 28, color: C.blue, marginLeft: 16}}>read-through</span>
            </Panel>
            <Panel style={{padding: '26px 34px', borderColor: 'rgba(251,191,36,0.35)', opacity: enter(frame, 134), translate: `0px ${(1 - enter(frame, 134)) * 22}px`}}>
              <span style={{fontFamily: FONT, fontSize: 36, color: C.text}}>④ 回源失败 → 旧缓存兜底</span>
              <span style={{fontFamily: MONO, fontSize: 28, color: C.amber, marginLeft: 16}}>哪怕已过期</span>
            </Panel>
          </div>

          <Panel style={{flex: 1, padding: '44px 48px', display: 'flex', flexDirection: 'column', gap: 30}}>
            <div style={{fontFamily: MONO, fontSize: 28, color: C.faint}}>backend.cachedApi.stats</div>
            <StatRow label="缓存命中" value={1284} start={40} color={C.green} />
            <StatRow label="回写缓存" value={312} start={70} color={C.blue} />
            <StatRow label="指纹未变跳过" value={47} start={100} color={C.cyan} />
            <StatRow label="过期兜底" value={3} start={130} color={C.amber} />
          </Panel>
        </div>

        <div style={{display: 'flex', gap: 60, justifyContent: 'center', marginTop: 56}}>
          <Bullet start={186} accent={C.cyan}>写操作不缓存 —— 加歌后，歌单下一次必是最新</Bullet>
          <Bullet start={200} accent={C.purple}>cookie 参与缓存键，多账号互不串台</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 05 · 本地流推送（300f） ---------- */

const STREAM_DEVICES: ReadonlyArray<{readonly name: string; readonly caption: string}> = [
  {name: '游戏内 radio', caption: 'game-radio:8420'},
  {name: '树莓派 · 音响', caption: 'raspberrypi:8420'},
  {name: '另一台 PC', caption: '192.168.31.7:8420'},
];

export const LinuxStream: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '104px 120px 0'}}>
        <Headline size={100} start={0}>
          整台电脑，都是你的<Grad>音频节点</Grad>
        </Headline>
        <Sub size={40} start={16} style={{marginTop: 16}}>
          HTTP 流输出 + 标准推送端点，第三方软件按契约直接对接
        </Sub>

        <div style={{display: 'flex', gap: 56, marginTop: 56}}>
          {/* 左：拓扑图 */}
          <div style={{width: 760, display: 'flex', flexDirection: 'column', gap: 26}}>
            <Panel style={{padding: '28px 36px', display: 'flex', alignItems: 'center', gap: 24, opacity: enter(frame, 10)}}>
              <LogoMark size={74} glow={false} />
              <div>
                <div style={{fontFamily: FONT, fontSize: 38, fontWeight: 700, color: C.text}}>CPPlayer · 本机</div>
                <div style={{fontFamily: MONO, fontSize: 26, color: C.cyan, marginTop: 6}}>
                  0.0.0.0:8080/stream · 支持断点续传
                </div>
              </div>
            </Panel>
            {STREAM_DEVICES.map((d, i) => {
              const p = enter(frame, 96 + i * 42);
              const lineP = enter(frame, 78 + i * 42, 20);
              return (
                <div key={d.name} style={{display: 'flex', alignItems: 'center', gap: 0}}>
                  <div style={{width: 64, height: 5, borderRadius: 3, background: 'rgba(255,255,255,0.1)', position: 'relative', overflow: 'hidden', marginLeft: 36}}>
                    <div style={{position: 'absolute', inset: 0, width: `${lineP * 100}%`, background: 'linear-gradient(90deg,#4A6CF7,#22D3EE)'}} />
                  </div>
                  <Chip accent="rgba(34,211,238,0.1)" size={32} style={{opacity: p, translate: `${(1 - p) * 40}px 0px`}}>
                    <span style={{fontWeight: 600}}>{d.name}</span>
                    <span style={{fontFamily: MONO, color: C.sub, fontSize: 26}}>{d.caption}</span>
                  </Chip>
                </div>
              );
            })}
          </div>

          {/* 右：终端 */}
          <Terminal
            title="curl — push"
            bodyStyle={{fontSize: 26, lineHeight: 1.75}}
            style={{flex: 1, opacity: enter(frame, 30), translate: `0px ${(1 - enter(frame, 30)) * 40}px`}}
          >
            <Typewriter text="$ curl -r 0- http://192.168.31.2:8080/stream \\" start={44} cpf={1.3} />
            <Typewriter text="      -o now-playing.mp3" start={86} cpf={1.3} style={{color: C.sub}} />
            <div style={{color: C.green, minHeight: '1.7em'}}>
              {frame >= 128 ? '← 206 Partial Content · 断点续传' : ' '}
            </div>
            <Typewriter text="$ curl -X POST \\" start={148} cpf={1.3} />
            <Typewriter text="  http://game-radio:8420/api/v1/play-url \\" start={166} cpf={1.3} style={{color: C.sub}} />
            <Typewriter
              text={"  -d '{\"url\":\"http://192.168.31.2:8080/stream\"}'"}
              start={206}
              cpf={1.3}
              style={{color: C.sub}}
            />
            <div style={{color: C.green, minHeight: '1.7em'}}>
              {frame >= 252 ? '← 200 OK · 游戏内 radio 已切歌' : ' '}
            </div>
          </Terminal>
        </div>

        <div style={{display: 'flex', gap: 28, justifyContent: 'center', marginTop: 48}}>
          <Chip size={32} style={{opacity: enter(frame, 258)}}>8080 · 本机流输出</Chip>
          <Chip size={32} style={{opacity: enter(frame, 268)}}>8420 · 接收端 API</Chip>
          <Chip size={32} style={{opacity: enter(frame, 278)}}>静默模式 · 本机音量 0</Chip>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 06 · 诊断（216f） ---------- */

const DIAG_BARS: ReadonlyArray<{readonly h: number; readonly color: string}> = Array.from(
  {length: 44},
  (_, i) => {
    const h = 36 + ((i * 53) % 72);
    const m = (i * 7) % 15;
    const color = m === 0 ? C.amber : m === 8 ? C.red : C.green;
    return {h, color};
  },
);

export const LinuxDiag: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '110px 120px 0'}}>
        <Headline size={104} start={0}>
          出问题？<Grad>先看诊断</Grad>
        </Headline>
        <Sub size={42} start={16} style={{marginTop: 16}}>
          三级健康分类 · 最近 100 条请求一览 · 顶部状态指示常驻
        </Sub>

        <div style={{display: 'flex', gap: 40, marginTop: 66, alignItems: 'stretch'}}>
          <Panel style={{flex: 1, padding: '44px 52px'}}>
            <div style={{display: 'flex', gap: 70, alignItems: 'center'}}>
              {[
                {label: 'OK', color: C.green, value: 96, start: 14},
                {label: 'WARNING', color: C.amber, value: 3, start: 26},
                {label: 'ERROR', color: C.red, value: 1, start: 38},
              ].map((l) => (
                <div key={l.label} style={{display: 'flex', alignItems: 'center', gap: 16, opacity: enter(frame, l.start)}}>
                  <span style={{width: 18, height: 18, borderRadius: 999, background: l.color, boxShadow: `0 0 18px ${l.color}`}} />
                  <span style={{fontFamily: MONO, fontSize: 34, color: C.text, fontWeight: 700}}>
                    {l.label} {Math.round(enter(frame, l.start, 30) * l.value)}
                  </span>
                </div>
              ))}
              <span style={{fontFamily: MONO, fontSize: 28, color: C.faint, marginLeft: 'auto'}}>
                recent 100 requests
              </span>
            </div>
            <div style={{display: 'flex', gap: 10, alignItems: 'flex-end', height: 190, marginTop: 44}}>
              {DIAG_BARS.map((b, i) => (
                <div
                  key={i}
                  style={{
                    flex: 1,
                    height: b.h * enter(frame, 40 + i * 3, 14),
                    minHeight: 4,
                    borderRadius: 5,
                    background: b.color,
                    opacity: 0.85,
                  }}
                />
              ))}
            </div>
          </Panel>

          {/* 真实界面：设置 → 诊断 */}
          <Panel
            style={{
              width: 620,
              flexShrink: 0,
              padding: '26px 28px',
              opacity: enter(frame, 60),
              translate: `0px ${(1 - enter(frame, 60)) * 24}px`,
            }}
          >
            <RealShot
              src="shots/shot-desktop-diagnostics.png"
              width={564}
              height={386}
              radius={12}
              style={{background: '#0D1322'}}
            />
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                marginTop: 16,
                fontFamily: FONT,
                fontSize: 24,
                color: C.faint,
              }}
            >
              <span
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: 999,
                  background: C.green,
                  boxShadow: `0 0 12px ${C.green}`,
                }}
              />
              真实界面 · 最近 100 条请求一览
            </div>
          </Panel>
        </div>

        <div style={{display: 'flex', gap: 60, justifyContent: 'center', marginTop: 56}}>
          <Bullet start={128} accent={C.green}>OK / WARNING / ERROR 三级分级</Bullet>
          <Bullet start={142} accent={C.cyan}>异常自动触发多 Provider 容灾</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 07 · KMP 分层（180f） ---------- */

const LayerBox: React.FC<{
  readonly title: string;
  readonly caption: string;
  readonly start: number;
  readonly width: number;
  readonly mono?: boolean;
}> = ({title, caption, start, width, mono}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start);
  return (
    <Panel
      style={{
        width,
        padding: '24px 36px',
        display: 'flex',
        alignItems: 'baseline',
        gap: 22,
        justifyContent: 'center',
        opacity: p,
        translate: `0px ${(1 - p) * 26}px`,
      }}
    >
      <span style={{fontFamily: mono ? MONO : FONT, fontSize: 38, fontWeight: 700, color: C.text}}>{title}</span>
      <span style={{fontFamily: FONT, fontSize: 27, color: C.sub}}>{caption}</span>
    </Panel>
  );
};

export const LinuxKmp: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '104px 120px 0'}}>
        <Headline size={100} start={0}>
          一次编写，<Grad>四端运行</Grad>
        </Headline>
        <Sub size={40} start={14} style={{marginTop: 14}}>
          Kotlin Multiplatform · Android 与桌面共用同一套播放内核与音源系统
        </Sub>

        <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', marginTop: 58, gap: 22}}>
          <LayerBox title="commonMain" caption="播放内核 · 音源插件系统 · 缓存 · API" start={8} width={980} mono />
          <div style={{fontFamily: MONO, fontSize: 36, color: C.purple, opacity: enter(frame, 38), lineHeight: 1}}>↓</div>
          <LayerBox title="jvmMain" caption="Socket · 二进制 Provider · 本地流输出" start={52} width={980} mono />
          <div style={{display: 'flex', gap: 320, fontFamily: MONO, fontSize: 36, color: C.purple, lineHeight: 1}}>
            <span style={{opacity: enter(frame, 84)}}>↙</span>
            <span style={{opacity: enter(frame, 92)}}>↘</span>
          </div>
          <div style={{display: 'flex', gap: 80}}>
            <LayerBox title="desktopMain" caption="rodio · JMTC · Skiko" start={108} width={640} mono />
            <LayerBox title="androidMain" caption="Media3 · JNI Provider" start={122} width={640} mono />
          </div>
        </div>

        <div style={{display: 'flex', gap: 26, justifyContent: 'center', marginTop: 52}}>
          {['Windows', 'macOS', 'Linux', 'Android'].map((p, i) => (
            <Chip key={p} size={34} accent="rgba(122,146,255,0.12)" style={{opacity: enter(frame, 138 + i * 10)}}>
              {p}
            </Chip>
          ))}
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 08 · 真软件实拍（240f） ---------- */

export const LinuxReal: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{padding: '104px 120px 0', alignItems: 'center'}}>
        <Headline size={100} start={0} align="center">
          不是 PPT，<Grad>是真软件</Grad>
        </Headline>
        <Sub size={40} start={14} align="center" style={{marginTop: 14}}>
          桌面端与 Android 端 · 实际运行界面截图
        </Sub>

        <div style={{display: 'flex', alignItems: 'flex-start', justifyContent: 'center', gap: 80, marginTop: 56}}>
          <div style={{opacity: enter(frame, 24), translate: `0px ${(1 - enter(frame, 24)) * 40}px`}}>
            <RealShot
              src="shots/shot-desktop-library.png"
              width={860}
              height={602}
              radius={14}
              style={{border: `1px solid ${C.border}`, background: '#0D1322', boxShadow: '0 36px 90px rgba(0,0,0,0.55)'}}
            />
            <div style={{display: 'flex', justifyContent: 'center', marginTop: 18}}>
              <Chip size={28} accent="rgba(122,146,255,0.12)">桌面端 · Windows / macOS / Linux</Chip>
            </div>
          </div>
          <div style={{opacity: enter(frame, 44), translate: `0px ${(1 - enter(frame, 44)) * 40}px`}}>
            <PhoneFrame width={286}>
              <RealShot
                src="shots/shot-android-home.jpg"
                style={{position: 'absolute', inset: 0}}
                imgStyle={{objectPosition: '50% 0%'}}
              />
            </PhoneFrame>
            <div style={{display: 'flex', justifyContent: 'center', marginTop: 18}}>
              <Chip size={28} accent="rgba(34,211,238,0.1)">Android 端 · 同一套内核</Chip>
            </div>
          </div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 09 · 收尾（90f） ---------- */

export const LinuxEnd: React.FC = () => (
  <EndCard
    tagline="为折腾而生的音乐播放器"
    note="Kotlin Multiplatform · Compose Multiplatform"
    chips={['Windows', 'macOS', 'Linux', 'Android']}
    start={6}
  />
);
