import React from 'react';
import {Img, staticFile} from 'remotion';

/**
 * 真实界面截图（public/ 下资源）。
 * 根容器负责定位/圆角/裁切，Img 以 objectFit:cover 填满；
 * imgStyle 可微调 objectPosition / scale（Ken Burns）等。
 */
export const RealShot: React.FC<{
  readonly src: string;
  readonly width?: number | string;
  readonly height?: number | string;
  readonly radius?: number;
  readonly style?: React.CSSProperties;
  readonly imgStyle?: React.CSSProperties;
}> = ({src, width, height, radius = 0, style, imgStyle}) => (
  <div
    style={{
      width,
      height,
      borderRadius: radius,
      overflow: 'hidden',
      position: 'relative',
      ...style,
    }}
  >
    <Img
      src={staticFile(src)}
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        objectFit: 'cover',
        ...imgStyle,
      }}
    />
  </div>
);
