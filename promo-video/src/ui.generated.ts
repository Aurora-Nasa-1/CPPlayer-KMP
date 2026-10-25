// 由 scripts/ui-manifest.mjs 生成，请勿手改（换图后重跑脚本）
export type UiCard = {file: string; name: string; w: number; h: number; kind: 'portrait' | 'landscape'};

export const UI_CARDS: UiCard[] = [];

export const HAS_UI = UI_CARDS.length > 0;
export const PORTRAITS = UI_CARDS.filter((c) => c.kind === 'portrait');
export const LANDSCAPES = UI_CARDS.filter((c) => c.kind === 'landscape');
