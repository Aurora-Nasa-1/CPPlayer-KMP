import React from 'react';
import {Img, staticFile} from 'remotion';
import {C, CARD_RATIO, FONT, MONO, shadow} from '../theme';
import type {CardLike} from '../cards';
import {LogoMark} from './Brand';

const PAD = 9;

// 一张「界面牌」：白卡 + 内嵌截图 + 左下角名称胶囊
export const UiCardView: React.FC<{
  card: CardLike;
  width: number;
  /** 固定高度：用于「窗口变窄」演示，只裁宽度不缩放 */
  fixedHeight?: number;
  radius?: number;
  style?: React.CSSProperties;
  pill?: boolean;
}> = ({card, width, fixedHeight, radius = 26, style, pill = true}) => {
  const height = fixedHeight ?? width * CARD_RATIO[card.kind];

  return (
    <div
      style={{
        width,
        height,
        background: C.surface,
        borderRadius: radius,
        border: `1px solid ${C.outline}`,
        boxShadow: shadow.float,
        padding: PAD,
        position: 'relative',
        flexShrink: 0,
        ...style,
      }}
    >
      <div
        style={{
          width: '100%',
          height: '100%',
          borderRadius: radius - 7,
          overflow: 'hidden',
          background: card.placeholder ? C.surfaceLow : C.surfaceC,
          position: 'relative',
        }}
      >
        {card.placeholder ? (
          <div
            style={{
              width: '100%',
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 12,
              backgroundImage: `repeating-linear-gradient(135deg, rgba(79,85,165,0.05) 0 12px, rgba(79,85,165,0.02) 12px 24px)`,
            }}
          >
            <div
              style={{
                fontFamily: FONT,
                fontSize: Math.max(15, width * 0.062),
                fontWeight: 700,
                color: C.inkVar,
                opacity: 0.75,
              }}
            >
              {card.name}
            </div>
            <div
              style={{
                fontFamily: MONO,
                fontSize: Math.max(11, width * 0.037),
                color: C.outlineStrong,
                letterSpacing: 1,
              }}
            >
              截图待补
            </div>
          </div>
        ) : (
          <Img
            src={staticFile(card.file)}
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'cover',
              objectPosition: card.kind === 'portrait' ? 'center top' : 'left top',
            }}
          />
        )}
      </div>

      {pill && (
        <div
          style={{
            position: 'absolute',
            left: 18,
            bottom: 18,
            display: 'flex',
            alignItems: 'center',
            gap: 7,
            padding: '6px 14px',
            borderRadius: 999,
            background: 'rgba(255,255,255,0.92)',
            border: `1px solid ${C.outline}`,
            fontFamily: FONT,
            fontSize: Math.max(13, width * 0.045),
            fontWeight: 600,
            color: C.ink,
            boxShadow: shadow.chip,
          }}
        >
          <span
            style={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: card.kind === 'portrait' ? C.primary : C.secondary,
            }}
          />
          {card.name}
        </div>
      )}
    </div>
  );
};

// 牌背：还没翻开时的那张脸
export const CardBack: React.FC<{width: number; radius?: number; style?: React.CSSProperties}> = ({
  width,
  radius = 26,
  style,
}) => (
  <div
    style={{
      width,
      height: width * CARD_RATIO.portrait,
      borderRadius: radius,
      background: `linear-gradient(150deg, ${C.primary} 0%, #6E63C4 45%, ${C.secondary} 100%)`,
      boxShadow: shadow.float,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      position: 'relative',
      overflow: 'hidden',
      flexShrink: 0,
      ...style,
    }}
  >
    <div
      style={{
        position: 'absolute',
        inset: 0,
        backgroundImage:
          'repeating-linear-gradient(135deg, rgba(255,255,255,0.10) 0 2px, rgba(255,255,255,0) 2px 18px)',
      }}
    />
    {/* 牌背正中的等化器标记：白色音量条，不带底色，压在渐变上才干净 */}
    <div style={{display: 'flex', alignItems: 'center', gap: width * 0.032}}>
      {[0.44, 0.74, 1, 0.62, 0.86, 0.4].map((h, i) => (
        <div
          key={i}
          style={{
            width: width * 0.052,
            height: width * 0.28 * h,
            borderRadius: width * 0.052,
            background: 'rgba(255,255,255,0.94)',
          }}
        />
      ))}
    </div>
  </div>
);
