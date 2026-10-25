import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Stage} from '../components/Stage';
import {UiCardView} from '../components/UiCard';
import {DUR} from '../timeline';
import {LANDSCAPES, PORTRAITS, fan, lerp, type CardLike} from '../cards';

const P_W = 310; // 手机牌宽
const L_W = 560; // 桌面牌宽
// 坐标基准是 Stage 的内容区（1680 × ~791），不是整帧 1920×1080
const CX = 840;
const CY = 395;

type Placed = {card: CardLike; x: number; y: number; rot: number; delay: number; z: number};

const buildRow = (cards: CardLike[], y: number, spacing: number, tilt: number) => {
  const positions = fan(cards.length, CX, y, spacing, tilt);
  return cards.map((card, i) => ({
    card,
    x: positions[i].x,
    y: positions[i].y,
    rot: positions[i].rot,
    delay: 0,
    z: 0,
  }));
};

export const S2Deck: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  const hasP = PORTRAITS.length > 0;
  const hasL = LANDSCAPES.length > 0;

  // 两组都在时，桌面牌在后上方、手机牌在前下方；只有一组就居中
  const lY = hasP ? 170 : CY;
  const pY = hasP ? 440 : CY;

  const rows: Placed[] = [];
  if (hasL) {
    buildRow(LANDSCAPES, lY, 232, 4.6).forEach((p, i) => {
      rows.push({...p, delay: 20 + i * 6, z: 10 - Math.abs(i - (LANDSCAPES.length - 1) / 2)});
    });
  }
  if (hasP) {
    buildRow(PORTRAITS, pY, 172, 5.2).forEach((p, i) => {
      rows.push({
        ...p,
        delay: 20 + (hasL ? LANDSCAPES.length * 6 : 0) + i * 6,
        z: 40 - Math.abs(i - (PORTRAITS.length - 1) / 2),
      });
    });
  }

  // 后段做一次轻微「分组」：桌面牌上浮、手机牌下沉，呼应「竖着拿 / 横着放」
  const sort = interpolate(frame, [250, 320], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <AbsoluteFill>
      <Stage index="02" label="界面" duration={DUR.deck} vo={1}>
        <AbsoluteFill>
          {rows.map((r) => {
            const p = spring({
              frame: frame - r.delay,
              fps,
              config: {damping: 17, mass: 0.85, stiffness: 72},
            });
            const isP = r.card.kind === 'portrait';
            const dy = isP ? sort * 18 : -sort * 24;
            const dx = (r.x - CX) * sort * 0.05;
            const x = lerp(CX, r.x + dx, p);
            const y = lerp(CY + 60, r.y + dy, p);
            const rot = lerp(0, r.rot, p) + Math.sin((frame + r.delay * 4) * 0.018) * 0.5;

            return (
              <div
                key={r.card.file + r.card.name}
                style={{
                  position: 'absolute',
                  left: 0,
                  top: 0,
                  zIndex: r.z,
                  opacity: Math.min(1, p * 1.4),
                  transform: `translate(${x}px, ${y}px) translate(-50%, -50%) rotate(${rot}deg) scale(${
                    0.74 + 0.26 * p
                  })`,
                }}
              >
                <UiCardView card={r.card} width={isP ? P_W : L_W} pill={isP} />
              </div>
            );
          })}
        </AbsoluteFill>
      </Stage>
    </AbsoluteFill>
  );
};
