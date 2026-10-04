import React from 'react';
import {Img, staticFile} from 'remotion';
import {C, FONT} from '../theme';

/** Windows 桌面窗口框（标题栏 + 最小化/最大化/关闭） */
export const DesktopWindow: React.FC<{
  readonly title?: string;
  readonly showIcon?: boolean;
  readonly style?: React.CSSProperties;
  readonly bodyStyle?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({title = 'CPPlayer', showIcon = true, style, bodyStyle, children}) => (
  <div
    style={{
      borderRadius: 14,
      border: `1px solid ${C.border}`,
      background: '#0D1322',
      boxShadow: '0 36px 90px rgba(0,0,0,0.55)',
      overflow: 'hidden',
      display: 'flex',
      flexDirection: 'column',
      ...style,
    }}
  >
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        paddingLeft: 18,
        height: 54,
        borderBottom: `1px solid ${C.border}`,
        background: 'rgba(255,255,255,0.05)',
        flexShrink: 0,
      }}
    >
      {showIcon ? (
        <Img
          src={staticFile('cpplayer-icon.png')}
          style={{width: 26, height: 26, borderRadius: 6, marginRight: 12}}
        />
      ) : null}
      <span style={{fontFamily: FONT, fontSize: 24, color: C.sub}}>{title}</span>
      <div style={{marginLeft: 'auto', display: 'flex', alignSelf: 'stretch'}}>
        <TitleBarGlyph>
          <span style={{width: 13, height: 1.6, background: C.sub, display: 'block'}} />
        </TitleBarGlyph>
        <TitleBarGlyph>
          <span
            style={{
              width: 12,
              height: 12,
              border: `1.6px solid ${C.sub}`,
              display: 'block',
            }}
          />
        </TitleBarGlyph>
        <TitleBarGlyph hover="#E81123">
          <span style={{fontFamily: FONT, fontSize: 17, color: C.sub, lineHeight: 1}}>
            ✕
          </span>
        </TitleBarGlyph>
      </div>
    </div>
    <div style={{position: 'relative', flex: 1, minHeight: 0, ...bodyStyle}}>
      {children}
    </div>
  </div>
);

const TitleBarGlyph: React.FC<{
  readonly children: React.ReactNode;
  readonly hover?: string;
  readonly style?: React.CSSProperties;
}> = ({children, hover, style}) => (
  <div
    style={{
      width: 56,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: 'transparent',
      ...style,
    }}
  >
    {children}
  </div>
);
