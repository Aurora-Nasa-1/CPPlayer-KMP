/**
 * M3E 形变动画的数学地基。
 *
 * 思路：把每个形状定义成**极坐标半径函数** r(t)，t∈[0,1) 为归一化角度。
 * 任意两个形状只需在相同 t 上对 r 做线性插值，就得到中间帧的**真形变**
 * （不是交叉淡入 —— 那种是「两张图叠化」，不是形变）。
 *
 * 采样成 SVG path 用折线即可：点数按尺寸自适应，圆滑度肉眼无法分辨，
 * 但比 Catmull-Rom 转贝塞尔快得多（每帧要重算几十条路径）。
 */

export type Polar = (t: number) => number;

const TAU = Math.PI * 2;

/** 圆形（基准，r≡1） */
export const polarCircle: Polar = () => 1;

/**
 * 超椭圆（squircle）：n=2 是圆，n=4 接近 M3 的圆角方，n→∞ 变正方。
 * 极坐标下 r = (|cosθ|^n + |sinθ|^n)^(-1/n)
 */
export const polarSquircle =
  (n = 4.2): Polar =>
  (t) => {
    const a = TAU * t;
    const c = Math.abs(Math.cos(a));
    const s = Math.abs(Math.sin(a));
    return 1 / Math.pow(Math.pow(c, n) + Math.pow(s, n), 1 / n);
  };

/** 菱形（L1 球面），r = 1/(|cosθ|+|sinθ|) */
export const polarDiamond: Polar = (t) => {
  const a = TAU * t;
  return 1 / (Math.abs(Math.cos(a)) + Math.abs(Math.sin(a)));
};

/**
 * n 瓣形。三参数控制「性格」：
 *   amp   —— 瓣的起伏幅度（越大越夸张）
 *   sharp —— 指数。>1 峰变尖、谷变平（阳光/爆炸）；≈1 圆润（花/饼干）；<1 峰变平
 *   bias  —— 峰谷相对 r=1 的偏移。0.5 对称；小值让整体偏大、只往下挖（饼干）
 */
export const polarLobes =
  (n: number, amp: number, sharp = 1, bias = 0.5): Polar =>
  (t) =>
    1 + amp * (Math.pow(Math.abs(Math.cos(Math.PI * n * t)), sharp) - bias);

/** 有机 blob（慢速 3 瓣，做背景/呼吸感） */
export const polarBlob = (n = 3, amp = 0.1): Polar => polarLobes(n, amp, 1, 0.5);

/** M3E 具名形状集（对应 MaterialShapes 的观感，非逐点复刻） */
export const SHAPES = {
  circle: polarCircle,
  /** 圆角方 */
  squircle: polarSquircle(4.4),
  /** 圆角方（更方） */
  square: polarSquircle(8),
  diamond: polarDiamond,
  /** 饼干：圆盘 + 浅扇贝边 */
  cookie6: polarLobes(6, 0.13, 1.35, 0.42),
  cookie9: polarLobes(9, 0.115, 1.35, 0.42),
  /** 花 / 四叶草：深瓣 */
  clover4: polarLobes(4, 0.42, 0.95, 0.5),
  flower6: polarLobes(6, 0.36, 1.0, 0.5),
  flower8: polarLobes(8, 0.3, 1.05, 0.5),
  /** 阳光 / 爆炸：尖锐射线 */
  sunny12: polarLobes(12, 0.36, 3.2, 0.16),
  sunny16: polarLobes(16, 0.3, 3.4, 0.16),
  burst8: polarLobes(8, 0.52, 2.3, 0.08),
  softBurst10: polarLobes(10, 0.3, 1.7, 0.2),
  /** 柔和有机 */
  blob: polarBlob(3, 0.11),
  blob5: polarBlob(5, 0.08),
} as const;

export type ShapeName = keyof typeof SHAPES;

/** 两个形状按 k∈[0,1] 插值 */
export const morphPolar = (a: Polar, b: Polar, k: number): Polar => {
  if (k <= 0) return a;
  if (k >= 1) return b;
  return (t) => a(t) * (1 - k) + b(t) * k;
};

export type ShapeKey = {at: number; shape: Polar};

/**
 * 关键帧形变轨道。`p` 为整条轨道的归一化进度 0→1，
 * 每段用 smoothstep 过渡，避免线性插值在关键帧处出现折角。
 */
export const morphTrack = (keys: readonly ShapeKey[], p: number): Polar => {
  if (keys.length === 0) return polarCircle;
  if (keys.length === 1) return keys[0].shape;
  const x = Math.max(0, Math.min(1, p));
  if (x <= keys[0].at) return keys[0].shape;
  const last = keys[keys.length - 1];
  if (x >= last.at) return last.shape;
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i];
    const b = keys[i + 1];
    if (x >= a.at && x <= b.at) {
      const span = b.at - a.at || 1;
      const raw = (x - a.at) / span;
      const k = raw * raw * (3 - 2 * raw); // smoothstep
      return morphPolar(a.shape, b.shape, k);
    }
  }
  return last.shape;
};

/** 按名字生成形变轨道（写场景时最常用） */
export const track = (...items: Array<[number, ShapeName]>): ShapeKey[] =>
  items.map(([at, name]) => ({at, shape: SHAPES[name]}));

/**
 * 采样成 SVG path。
 * @param size   画布边长（正方形），路径坐标直接落在 0..size
 * @param rot    额外旋转（弧度）
 * @param wobble 每点抖动幅度（0 关闭）——给静态形状一点「活着」的感觉
 */
export const shapePath = (
  r: Polar,
  size: number,
  opts: {rot?: number; wobble?: number; seed?: number; samples?: number} = {},
): string => {
  const {rot = 0, wobble = 0, seed = 0, samples} = opts;
  const n = samples ?? Math.max(160, Math.min(420, Math.round(size / 3)));
  const half = size / 2;
  let d = '';
  for (let i = 0; i < n; i++) {
    const t = i / n;
    const ang = TAU * t + rot;
    let rr = r(t);
    if (wobble > 0) {
      rr += wobble * (Math.sin(TAU * 3 * t + seed * 1.7) * 0.6 + Math.sin(TAU * 7 * t + seed * 3.1) * 0.4);
    }
    const x = half + half * rr * Math.cos(ang);
    const y = half + half * rr * Math.sin(ang);
    d += (i === 0 ? 'M' : 'L') + x.toFixed(2) + ',' + y.toFixed(2);
  }
  return d + 'Z';
};

/**
 * 真实周长。**不能用 3.14×size 之类的估算** —— 瓣状形状（sunny/burst）周长比圆长得多，
 * 估算值会让 stroke-dasharray 的"画线"动画在收尾处露出一截或提前闭合。
 */
export const shapePerimeter = (r: Polar, size: number, samples = 360): number => {
  const half = size / 2;
  let sum = 0;
  let px = 0;
  let py = 0;
  for (let i = 0; i <= samples; i++) {
    const t = i / samples;
    const a = TAU * t;
    const rr = half * r(t);
    const x = half + rr * Math.cos(a);
    const y = half + rr * Math.sin(a);
    if (i > 0) sum += Math.hypot(x - px, y - py);
    px = x;
    py = y;
  }
  return sum;
};

/**
 * 波形路径（M3E 的 LinearWavyProgressIndicator 用）。
 * 返回一条开放的横向正弦曲线，从 (0, h/2) 到 (w, h/2)。
 */
export const wavePath = (
  w: number,
  h: number,
  opts: {amp?: number; period?: number; phase?: number; y?: number} = {},
): string => {
  const {amp = h * 0.3, period = 46, phase = 0, y = h / 2} = opts;
  const step = 3;
  let d = '';
  for (let x = 0; x <= w; x += step) {
    const yy = y + amp * Math.sin((x / period) * TAU + phase);
    d += (x === 0 ? 'M' : 'L') + x.toFixed(2) + ',' + yy.toFixed(2);
  }
  return d;
};
