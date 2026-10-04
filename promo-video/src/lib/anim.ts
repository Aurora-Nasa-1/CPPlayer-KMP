import {Easing, interpolate} from 'remotion';

export const EASE = Easing.bezier(0.16, 1, 0.3, 1);

/** 0→1 入场进度（软出缓动） */
export const enter = (frame: number, start: number, dur = 18): number =>
  interpolate(frame, [start, start + dur], [0, 1], {
    easing: EASE,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

/** 末尾淡出进度 1→0（场景内部自退场用，一般交给 TransitionSeries） */
export const leave = (
  frame: number,
  total: number,
  dur = 14,
): number =>
  interpolate(frame, [total - dur, total], [1, 0], {
    easing: EASE,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

export const px = (n: number): string => `${n}px`;
