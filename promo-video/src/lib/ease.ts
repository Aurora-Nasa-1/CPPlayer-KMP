import {Easing, interpolate, spring} from 'remotion';

/** M3E 的标准「强调」缓动（Emphasized decelerate 的近似） */
export const EASE = Easing.bezier(0.16, 1, 0.3, 1);
/** M3E Emphasized accelerate（离场用，比入场更急） */
export const EASE_IN = Easing.bezier(0.3, 0, 0.8, 0.15);
export const EASE_IO = Easing.bezier(0.42, 0, 0.58, 1);
/**
 * easeInOutSine。**形变轨道必须用它**：EASE 的前段太陡，
 * 多关键帧形变会被一口气推到最后一段，中间的形状根本来不及看见。
 */
export const EASE_SINE = Easing.bezier(0.37, 0, 0.63, 1);

/** 在 [a,b] 帧区间内做 0→1，区间外保持 0 / 1 —— 整片的分段编排全靠它 */
export const seg = (frame: number, a: number, b: number, easing = EASE): number =>
  interpolate(frame, [a, b], [0, 1], {
    easing,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

/**
 * 弹簧进度 0→1。M3E 的「表现力」很大程度来自这个过冲。
 * Remotion 的 spring 必须传 fps。
 */
export const springySettle = (
  frame: number,
  fps: number,
  delay = 0,
  cfg: {damping?: number; mass?: number; stiffness?: number} = {},
): number =>
  spring({
    frame: frame - delay,
    fps,
    config: {damping: 26, mass: 0.8, stiffness: 130, ...cfg},
  });

/** 由「进度 0→1」直接插值（已有 p 时用） */
export const mix = (p: number, a: number, b: number): number => a + (b - a) * p;
