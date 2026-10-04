import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C, FONT, MONO} from '../../theme';
import {enter} from '../../lib/anim';
import {Backdrop} from '../../components/Backdrop';
import {Bullet, Chip, Grad, Headline, Panel, Sub} from '../../components/Bits';
import {LogoMark} from '../../components/LogoMark';
import {PhoneFrame} from '../../components/PhoneFrame';
import {EndCard} from '../../components/EndCard';

/* ---------- 竖屏通用小件 ---------- */

const PlayTri: React.FC<{readonly size?: number; readonly color?: string}> = ({size = 20, color = '#fff'}) => (
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

const PauseBars: React.FC<{readonly size?: number; readonly color?: string}> = ({size = 20, color = '#fff'}) => (
  <span style={{display: 'flex', gap: size * 0.24}}>
    <span style={{width: size * 0.26, height: size * 1.2, background: color, borderRadius: 2}} />
    <span style={{width: size * 0.26, height: size * 1.2, background: color, borderRadius: 2}} />
  </span>
);

const Cover: React.FC<{readonly size: number; readonly radius?: number; readonly hue?: string}> = ({
  size,
  radius = 14,
  hue = 'linear-gradient(135deg,#4A6CF7 0%,#8B5CF6 100%)',
}) => (
  <div
    style={{
      width: size,
      height: size,
      borderRadius: radius,
      background: hue,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0,
    }}
  >
    <span style={{fontFamily: FONT, fontSize: size * 0.32, color: 'rgba(255,255,255,0.85)'}}>♪</span>
  </div>
);

/* ---------- 01 · Hook：手机主界面（240f） ---------- */

export const AndroidHook: React.FC = () => {
  const frame = useCurrentFrame();
  const p = enter(frame, 6);
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{alignItems: 'center', paddingTop: 130}}>
        <Headline size={92} start={0} align="center">
          同一套内核，<Grad>装进口袋</Grad>
        </Headline>
        <Sub size={40} start={16} align="center" style={{marginTop: 16}}>
          CPPlayer for Android · Kotlin Multiplatform
        </Sub>

        <PhoneFrame
          width={560}
          style={{marginTop: 56, opacity: p, translate: `0px ${(1 - p) * 70}px`}}
        >
          <div style={{position: 'absolute', inset: 0, padding: '34px 26px 0'}}>
            <div style={{display: 'flex', justifyContent: 'space-between', fontFamily: MONO, fontSize: 24, color: C.sub}}>
              <span>21:32</span>
              <span>▾ ▮</span>
            </div>
            <div style={{display: 'flex', alignItems: 'center', gap: 14, marginTop: 26}}>
              <LogoMark size={44} glow={false} />
              <span style={{fontFamily: FONT, fontSize: 32, fontWeight: 700, color: C.text}}>CPPlayer</span>
            </div>
            <div style={{fontFamily: FONT, fontSize: 40, fontWeight: 700, color: C.text, marginTop: 34, opacity: enter(frame, 30)}}>
              晚上好
            </div>
            <div style={{fontFamily: FONT, fontSize: 26, color: C.sub, marginTop: 8, opacity: enter(frame, 38)}}>
              10月4日 · 周日
            </div>

            <div
              style={{
                marginTop: 30,
                borderRadius: 24,
                background: 'linear-gradient(135deg,#4A6CF7 0%,#8B5CF6 100%)',
                padding: '30px 30px',
                display: 'flex',
                alignItems: 'center',
                opacity: enter(frame, 52),
                translate: `0px ${(1 - enter(frame, 52)) * 26}px`,
              }}
            >
              <div style={{flex: 1}}>
                <div style={{fontFamily: FONT, fontSize: 28, color: 'rgba(255,255,255,0.85)'}}>今日推荐</div>
                <div style={{fontFamily: FONT, fontSize: 38, fontWeight: 700, color: '#fff', marginTop: 8}}>
                  Daily Mix · 30 首
                </div>
              </div>
              <div
                style={{
                  width: 74,
                  height: 74,
                  borderRadius: 999,
                  background: 'rgba(255,255,255,0.2)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <PlayTri size={26} />
              </div>
            </div>

            <div style={{display: 'flex', gap: 18, marginTop: 20}}>
              {['曲库', '搜索'].map((t, i) => (
                <div
                  key={t}
                  style={{
                    flex: 1,
                    borderRadius: 20,
                    border: `1px solid ${C.border}`,
                    background: 'rgba(255,255,255,0.05)',
                    padding: '26px 26px',
                    fontFamily: FONT,
                    fontSize: 30,
                    fontWeight: 600,
                    color: C.text,
                    opacity: enter(frame, 74 + i * 12),
                    translate: `0px ${(1 - enter(frame, 74 + i * 12)) * 20}px`,
                  }}
                >
                  {t}
                </div>
              ))}
            </div>

            <div style={{fontFamily: FONT, fontSize: 28, color: C.sub, marginTop: 34, opacity: enter(frame, 104)}}>
              最近播放
            </div>
            <div style={{display: 'flex', gap: 16, marginTop: 16}}>
              {[
                'linear-gradient(135deg,#4A6CF7,#22D3EE)',
                'linear-gradient(135deg,#8B5CF6,#EC4899)',
                'linear-gradient(135deg,#34D399,#4A6CF7)',
              ].map((hue, i) => (
                <div key={i} style={{opacity: enter(frame, 116 + i * 10)}}>
                  <Cover size={138} radius={18} hue={hue} />
                </div>
              ))}
            </div>

            <div style={{display: 'flex', gap: 18, marginTop: 22}}>
              {['专辑', '歌手', '歌单'].map((t, i) => (
                <div
                  key={t}
                  style={{
                    flex: 1,
                    borderRadius: 18,
                    border: `1px solid ${C.border}`,
                    background: 'rgba(255,255,255,0.05)',
                    padding: '20px 22px',
                    fontFamily: FONT,
                    fontSize: 26,
                    fontWeight: 600,
                    color: C.text,
                    opacity: enter(frame, 140 + i * 10),
                    translate: `0px ${(1 - enter(frame, 140 + i * 10)) * 18}px`,
                  }}
                >
                  {t}
                </div>
              ))}
            </div>

            <div
              style={{
                position: 'absolute',
                left: 20,
                right: 20,
                bottom: 30,
                display: 'flex',
                alignItems: 'center',
                gap: 16,
                borderRadius: 999,
                background: 'rgba(255,255,255,0.08)',
                border: `1px solid ${C.border}`,
                padding: '14px 22px',
                opacity: enter(frame, 150),
                translate: `0px ${(1 - enter(frame, 150)) * 24}px`,
              }}
            >
              <Cover size={56} radius={12} />
              <div style={{flex: 1}}>
                <div style={{fontFamily: FONT, fontSize: 26, color: C.text, fontWeight: 600}}>Night Drive</div>
                <div style={{fontFamily: FONT, fontSize: 21, color: C.sub}}>Aurora Keys</div>
              </div>
              <PlayTri size={18} color={C.sub} />
            </div>
          </div>
        </PhoneFrame>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 02 · 通知栏 / 锁屏控制（270f） ---------- */

export const AndroidNotif: React.FC = () => {
  const frame = useCurrentFrame();
  const playing = frame < 168;
  const progress = 0.42 + Math.min(1, Math.max(0, (frame - 36) / 200)) * 0.5;
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintB="rgba(34,211,238,0.2)" />
      <AbsoluteFill style={{alignItems: 'center', paddingTop: 130}}>
        <Headline size={92} start={0} align="center">
          通知栏、锁屏，<Grad>随手控</Grad>
        </Headline>
        <Sub size={40} start={16} align="center" style={{marginTop: 16}}>
          Media3 媒体会话 · 锁屏封面与控制 · 熄屏继续播
        </Sub>

        <PhoneFrame width={560} style={{marginTop: 56}}>
          <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(180deg,#0D1428 0%,#0A0F1E 100%)'}} />
          <div style={{position: 'absolute', inset: 0, padding: '90px 26px 0'}}>
            <div style={{fontFamily: MONO, fontSize: 120, fontWeight: 200, color: C.text, textAlign: 'center', opacity: enter(frame, 10)}}>
              21:32
            </div>
            <div style={{fontFamily: FONT, fontSize: 28, color: C.sub, textAlign: 'center', marginTop: 6, opacity: enter(frame, 20)}}>
              10月4日 周日
            </div>

            <div
              style={{
                marginTop: 60,
                borderRadius: 28,
                background: 'rgba(255,255,255,0.07)',
                border: `1px solid ${C.border}`,
                padding: '28px 26px',
                backdropFilter: 'blur(8px)',
                opacity: enter(frame, 44),
                translate: `0px ${(1 - enter(frame, 44)) * 40}px`,
              }}
            >
              <div style={{display: 'flex', alignItems: 'center', gap: 12}}>
                <LogoMark size={30} glow={false} />
                <span style={{fontFamily: FONT, fontSize: 24, color: C.sub}}>CPPlayer · 正在播放</span>
                <span style={{marginLeft: 'auto', fontFamily: FONT, fontSize: 22, color: C.faint}}>展开</span>
              </div>
              <div style={{display: 'flex', gap: 20, marginTop: 24, alignItems: 'center'}}>
                <Cover size={110} radius={16} />
                <div style={{flex: 1}}>
                  <div style={{fontFamily: FONT, fontSize: 30, fontWeight: 600, color: C.text}}>Night Drive</div>
                  <div style={{fontFamily: FONT, fontSize: 24, color: C.sub, marginTop: 6}}>Aurora Keys</div>
                </div>
              </div>
              <div style={{height: 8, borderRadius: 4, background: 'rgba(255,255,255,0.12)', marginTop: 26}}>
                <div style={{width: `${progress * 100}%`, height: '100%', borderRadius: 4, background: 'linear-gradient(90deg,#4A6CF7,#8B5CF6)'}} />
              </div>
              <div style={{display: 'flex', justifyContent: 'center', gap: 44, marginTop: 26, alignItems: 'center'}}>
                <span style={{transform: 'scaleX(-1)', display: 'block', opacity: enter(frame, 60)}}>
                  <PlayTri size={22} color={C.text} />
                </span>
                <div
                  style={{
                    width: 84,
                    height: 84,
                    borderRadius: 999,
                    background: 'linear-gradient(135deg,#4A6CF7,#8B5CF6)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    boxShadow: '0 14px 36px rgba(74,108,247,0.45)',
                  }}
                >
                  {playing ? <PlayTri size={26} /> : <PauseBars size={26} />}
                </div>
                <span style={{opacity: enter(frame, 60)}}>
                  <PlayTri size={22} color={C.text} />
                </span>
              </div>
            </div>

            {/* 锁屏底部提示 */}
            <div
              style={{
                position: 'absolute',
                bottom: 56,
                left: 0,
                right: 0,
                textAlign: 'center',
                fontFamily: FONT,
                fontSize: 25,
                color: C.faint,
                opacity: enter(frame, 100),
              }}
            >
              上滑解锁
            </div>
          </div>
        </PhoneFrame>

        <div style={{display: 'flex', gap: 56, marginTop: 60}}>
          <Bullet start={190} size={38} accent={C.cyan}>耳机线控 / 蓝牙按钮同样生效</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 03 · 后台不断播（240f） ---------- */

export const AndroidBack: React.FC = () => {
  const frame = useCurrentFrame();
  const shrink = enter(frame, 44, 34);
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintA="rgba(52,211,153,0.22)" />
      <AbsoluteFill style={{alignItems: 'center', paddingTop: 130}}>
        <Headline size={92} start={0} align="center">
          切走应用，<Grad>音乐不断</Grad>
        </Headline>
        <Sub size={40} start={16} align="center" style={{marginTop: 16}}>
          前台服务保活 · 熄屏播放稳定
        </Sub>

        <PhoneFrame width={560} style={{marginTop: 56}}>
          <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(ellipse at 30% 20%, rgba(74,108,247,0.35), transparent 55%), radial-gradient(ellipse at 75% 80%, rgba(139,92,246,0.3), transparent 55%), #070B16'}} />
          {/* 常驻的通知胶囊 */}
          <div
            style={{
              position: 'absolute',
              top: 60,
              left: 24,
              right: 24,
              display: 'flex',
              alignItems: 'center',
              gap: 14,
              borderRadius: 999,
              background: 'rgba(12,18,34,0.92)',
              border: `1px solid ${C.border}`,
              padding: '14px 22px',
              zIndex: 4,
              opacity: enter(frame, 20),
            }}
          >
            <LogoMark size={34} glow={false} />
            <span style={{fontFamily: FONT, fontSize: 24, color: C.text, fontWeight: 600}}>CPPlayer</span>
            <div style={{display: 'flex', gap: 5, alignItems: 'flex-end', height: 22, marginLeft: 4}}>
              {[0, 1, 2, 3].map((i) => (
                <span
                  key={i}
                  style={{
                    width: 5,
                    borderRadius: 3,
                    background: C.cyan,
                    height: 8 + Math.abs(Math.sin(frame / 7 + i)) * 14,
                  }}
                />
              ))}
            </div>
            <span style={{marginLeft: 'auto'}}>
              <PlayTri size={16} color={C.sub} />
            </span>
          </div>

          {/* 应用窗口缩成卡片 */}
          <div
            style={{
              position: 'absolute',
              left: 30 + shrink * 34,
              right: 30 + shrink * 34,
              top: 150 - shrink * 10,
              bottom: 150 - shrink * 10,
              borderRadius: 28,
              border: `1px solid ${shrink > 0.5 ? 'rgba(122,146,255,0.5)' : 'rgba(255,255,255,0.1)'}`,
              background: '#0C1222',
              boxShadow: shrink > 0.5 ? '0 30px 70px rgba(0,0,0,0.6)' : 'none',
              overflow: 'hidden',
              scale: `${1 - shrink * 0.12}`,
              translate: `0px ${shrink * -14}px`,
            }}
          >
            <div style={{padding: '28px 26px'}}>
              <div style={{display: 'flex', alignItems: 'center', gap: 14}}>
                <LogoMark size={38} glow={false} />
                <span style={{fontFamily: FONT, fontSize: 26, fontWeight: 700, color: C.text}}>CPPlayer</span>
              </div>
              <div style={{display: 'flex', gap: 14, marginTop: 26}}>
                <Cover size={110} radius={16} />
                <div style={{flex: 1, display: 'flex', flexDirection: 'column', gap: 14, justifyContent: 'center'}}>
                  <div style={{height: 16, width: '80%', borderRadius: 8, background: 'rgba(255,255,255,0.1)'}} />
                  <div style={{height: 16, width: '55%', borderRadius: 8, background: 'rgba(255,255,255,0.07)'}} />
                  <div style={{height: 16, width: '68%', borderRadius: 8, background: 'rgba(255,255,255,0.07)'}} />
                </div>
              </div>
              <div style={{display: 'flex', gap: 12, marginTop: 26}}>
                {[0, 1, 2].map((i) => (
                  <div key={i} style={{flex: 1, height: 90, borderRadius: 16, background: 'rgba(255,255,255,0.05)'}} />
                ))}
              </div>
            </div>
          </div>

          {/* 桌面时的小字 */}
          <div
            style={{
              position: 'absolute',
              bottom: 92,
              left: 0,
              right: 0,
              textAlign: 'center',
              fontFamily: FONT,
              fontSize: 26,
              color: C.sub,
              opacity: shrink,
            }}
          >
            已回到桌面 · 播放继续
          </div>
        </PhoneFrame>

        <div style={{display: 'flex', gap: 56, marginTop: 60}}>
          <Bullet start={170} size={38} accent={C.green}>切应用 / 回桌面，播放不中断</Bullet>
        </div>
        <div style={{display: 'flex', gap: 56, marginTop: 8}}>
          <Bullet start={186} size={38} accent={C.cyan}>熄屏听歌，省电又稳定</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 04 · 省流量缓存（252f） ---------- */

export const AndroidData: React.FC = () => {
  const frame = useCurrentFrame();
  const p = enter(frame, 40, 60);
  const pct = Math.round(p * 87);
  const r = 150;
  const circ = 2 * Math.PI * r;
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintA="rgba(52,211,153,0.2)" />
      <AbsoluteFill style={{padding: '150px 100px 0'}}>
        <Headline size={96} start={0} align="center">
          省流量，<Grad>也省心</Grad>
        </Headline>
        <Sub size={40} start={16} align="center" style={{marginTop: 16}}>
          读透缓存 · 重复内容不再下载 · 写操作即时失效
        </Sub>

        <div style={{display: 'flex', alignItems: 'center', gap: 70, marginTop: 130, justifyContent: 'center'}}>
          <div style={{position: 'relative', width: 360, height: 360, flexShrink: 0, opacity: enter(frame, 24)}}>
            <svg width={360} height={360} viewBox="0 0 360 360" style={{transform: 'rotate(-90deg)'}}>
              <defs>
                <linearGradient id="ringGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stopColor="#4A6CF7" />
                  <stop offset="100%" stopColor="#34D399" />
                </linearGradient>
              </defs>
              <circle cx={180} cy={180} r={r} fill="none" stroke="rgba(255,255,255,0.09)" strokeWidth={26} />
              <circle
                cx={180}
                cy={180}
                r={r}
                fill="none"
                stroke="url(#ringGrad)"
                strokeWidth={26}
                strokeLinecap="round"
                strokeDasharray={circ}
                strokeDashoffset={circ * (1 - (pct / 100))}
              />
            </svg>
            <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'}}>
              <span style={{fontFamily: MONO, fontSize: 84, fontWeight: 700, color: C.text}}>{pct}%</span>
              <span style={{fontFamily: FONT, fontSize: 30, color: C.sub, marginTop: 6}}>缓存命中率</span>
            </div>
          </div>

          <div style={{display: 'flex', flexDirection: 'column', gap: 24, width: 480}}>
            {[
              {t: '搜索结果 · 秒开', d: '缓存命中', c: C.green, start: 80},
              {t: '歌单详情 · 回源刷新', d: '指纹比对，没变就不重复拉', c: C.blue, start: 120},
              {t: '回源失败 · 旧缓存兜底', d: '界面照样有内容', c: C.amber, start: 160},
            ].map((row) => (
              <Panel
                key={row.t}
                style={{padding: '26px 32px', opacity: enter(frame, row.start), translate: `0px ${(1 - enter(frame, row.start)) * 22}px`}}
              >
                <div style={{fontFamily: FONT, fontSize: 32, color: C.text, fontWeight: 600}}>{row.t}</div>
                <div style={{fontFamily: FONT, fontSize: 25, color: row.c, marginTop: 8}}>{row.d}</div>
              </Panel>
            ))}
          </div>
        </div>

        <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 22, marginTop: 150}}>
          <Bullet start={196} size={38} accent={C.cyan}>cookie 参与缓存键 · 多账号互不串台</Bullet>
          <Bullet start={212} size={38} accent={C.purple}>点赞 / 加歌后，相关缓存立即失效</Bullet>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 05 · 离线下载（246f） ---------- */

const OFF_ROWS: ReadonlyArray<{readonly title: string; readonly artist: string; readonly size: string; readonly start: number}> = [
  {title: 'Night Drive', artist: 'Aurora Keys', size: '38.2 MB', start: 36},
  {title: 'City Rain', artist: 'Mochi', size: '31.5 MB', start: 78},
  {title: '深夜公交', artist: '南屿', size: '42.8 MB', start: 120},
];

export const AndroidOffline: React.FC = () => {
  const frame = useCurrentFrame();
  const saved = Math.round(enter(frame, 170, 40) * 1.2 * 10) / 10;
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop tintB="rgba(139,92,246,0.32)" />
      <AbsoluteFill style={{padding: '150px 100px 0'}}>
        <Headline size={96} start={0} align="center">
          先下好，<Grad>路上听</Grad>
        </Headline>
        <Sub size={40} start={16} align="center" style={{marginTop: 16}}>
          离线下载管理 · 地铁 / 弱网也不怕
        </Sub>

        <Panel style={{marginTop: 80, padding: '40px 44px', opacity: enter(frame, 20)}}>
          <div style={{display: 'flex', alignItems: 'center'}}>
            <span style={{fontFamily: FONT, fontSize: 34, fontWeight: 700, color: C.text}}>下载队列</span>
            <span style={{fontFamily: MONO, fontSize: 26, color: C.faint, marginLeft: 'auto'}}>3 首任务</span>
          </div>
          <div style={{display: 'flex', flexDirection: 'column', gap: 34, marginTop: 38}}>
            {OFF_ROWS.map((row, i) => {
              const p = Math.min(1, Math.max(0, (frame - row.start) / 76));
              const done = p >= 1;
              return (
                <div key={i} style={{display: 'flex', alignItems: 'center', gap: 22}}>
                  <Cover size={92} radius={16} hue={`linear-gradient(135deg,${i % 2 ? '#8B5CF6' : '#4A6CF7'},${i % 2 ? '#EC4899' : '#22D3EE'})`} />
                  <div style={{flex: 1}}>
                    <div style={{display: 'flex', alignItems: 'baseline'}}>
                      <span style={{fontFamily: FONT, fontSize: 32, fontWeight: 600, color: C.text}}>{row.title}</span>
                      <span style={{fontFamily: FONT, fontSize: 25, color: C.sub, marginLeft: 16}}>{row.artist}</span>
                      <span
                        style={{
                          marginLeft: 'auto',
                          fontFamily: MONO,
                          fontSize: 26,
                          color: done ? C.green : C.sub,
                        }}
                      >
                        {done ? '✓ 已下载' : row.size}
                      </span>
                    </div>
                    <div style={{height: 12, borderRadius: 6, background: 'rgba(255,255,255,0.1)', marginTop: 14}}>
                      <div style={{width: `${p * 100}%`, height: '100%', borderRadius: 6, background: done ? C.green : 'linear-gradient(90deg,#4A6CF7,#8B5CF6)'}} />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Panel>

        <div style={{display: 'flex', justifyContent: 'center', marginTop: 70}}>
          <Chip size={38} accent="rgba(52,211,153,0.12)" style={{opacity: enter(frame, 176)}}>
            本月已省流量 <span style={{fontFamily: MONO, color: C.green}}> {saved.toFixed(1)} GB</span>
          </Chip>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 06 · 播放页（240f） ---------- */

const LYRICS = ['穿过晚高峰的灯河', '耳机里的城市慢慢降落', '这一站，刚好到我家'];

export const AndroidPlayer: React.FC = () => {
  const frame = useCurrentFrame();
  const activeIdx = frame < 80 ? 0 : frame < 150 ? 1 : 2;
  return (
    <AbsoluteFill style={{overflow: 'hidden'}}>
      <Backdrop />
      <AbsoluteFill style={{alignItems: 'center', paddingTop: 120}}>
        <Headline size={92} start={0} align="center">
          该有的，<Grad>都有</Grad>
        </Headline>
        <Sub size={40} start={14} align="center" style={{marginTop: 14}}>
          歌词 · 队列 · 评论 · 音质可选 · 歌单随手增删
        </Sub>

        <PhoneFrame width={560} style={{marginTop: 48}}>
          <div style={{position: 'absolute', inset: 0, padding: '40px 30px 0'}}>
            <div style={{display: 'flex', justifyContent: 'space-between', fontFamily: FONT, fontSize: 24, color: C.sub}}>
              <span>‹ 返回</span>
              <span>更多</span>
            </div>
            <div style={{display: 'flex', justifyContent: 'center', marginTop: 34, opacity: enter(frame, 12)}}>
              <Cover size={300} radius={28} />
            </div>
            <div style={{textAlign: 'center', marginTop: 30, opacity: enter(frame, 26)}}>
              <div style={{fontFamily: FONT, fontSize: 38, fontWeight: 700, color: C.text}}>Night Drive</div>
              <div style={{fontFamily: FONT, fontSize: 26, color: C.sub, marginTop: 8}}>Aurora Keys · 无损</div>
            </div>
            <div style={{height: 8, borderRadius: 4, background: 'rgba(255,255,255,0.12)', marginTop: 26, opacity: enter(frame, 34)}}>
              <div style={{width: `${30 + (frame % 100) * 0.6}%`, height: '100%', borderRadius: 4, background: 'linear-gradient(90deg,#4A6CF7,#8B5CF6)'}} />
            </div>
            <div style={{display: 'flex', justifyContent: 'center', gap: 40, marginTop: 26, alignItems: 'center', opacity: enter(frame, 40)}}>
              <span style={{transform: 'scaleX(-1)', display: 'block'}}>
                <PlayTri size={20} color={C.text} />
              </span>
              <div
                style={{
                  width: 78,
                  height: 78,
                  borderRadius: 999,
                  background: 'linear-gradient(135deg,#4A6CF7,#8B5CF6)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <PlayTri size={24} />
              </div>
              <PlayTri size={20} color={C.text} />
            </div>

            <div
              style={{
                marginTop: 34,
                borderRadius: 20,
                background: 'rgba(255,255,255,0.05)',
                border: `1px solid ${C.border}`,
                padding: '24px 26px',
                display: 'flex',
                flexDirection: 'column',
                gap: 16,
                opacity: enter(frame, 54),
              }}
            >
              {LYRICS.map((line, i) => {
                const active = i === activeIdx;
                return (
                  <div
                    key={line}
                    style={{
                      fontFamily: FONT,
                      fontSize: active ? 32 : 27,
                      fontWeight: active ? 700 : 400,
                      color: active ? C.text : C.faint,
                      translate: `0px ${active ? -2 : 0}px`,
                      transition: 'none',
                    }}
                  >
                    {line}
                  </div>
                );
              })}
            </div>

            <div style={{display: 'flex', gap: 14, justifyContent: 'center', marginTop: 30}}>
              {['无损', '队列 12', '评论 2.1k'].map((t, i) => (
                <Chip key={t} size={26} style={{opacity: enter(frame, 80 + i * 10)}}>{t}</Chip>
              ))}
            </div>
          </div>
        </PhoneFrame>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 07 · 收尾（120f） ---------- */

export const AndroidEnd: React.FC = () => (
  <EndCard
    tagline="口袋里的 CPPlayer"
    note="与桌面同源 · APK 直装"
    chips={['Android']}
    compact
    start={6}
  />
);
