import {VO} from './vo.generated';

// 场景与口播共用同一份时间轴：场景时长由配音长度决定，不手写。
export const SCENES = VO;

export const DUR = {
  hook: VO[0].dur,
  deck: VO[1].dur,
  spot: VO[2].dur,
  provider: VO[3].dur,
  outro: VO[4].dur,
} as const;
