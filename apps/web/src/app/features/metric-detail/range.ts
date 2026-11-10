/** Largest bucket count the API returns for one query. */
export const MAX_BUCKETS = 2000;
/** What the chart aims for: dense enough to show a spike, light enough to draw and to read in a table. */
export const TARGET_BUCKETS = 400;
/** The API rejects a longer range. */
export const MAX_RANGE_DAYS = 366;

/** Bucket widths the API picks from, in seconds (the same list, so a chosen step is always valid). */
export const STEP_SECONDS: readonly number[] = [
  1, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10_800, 21_600, 43_200, 86_400,
  172_800, 604_800,
];

export type RangePresetId = '1h' | '6h' | '24h' | '7d';

export interface RangePreset {
  readonly id: RangePresetId;
  readonly label: string;
  readonly ms: number;
}

const HOUR = 3_600_000;

export const RANGE_PRESETS: readonly RangePreset[] = [
  { id: '1h', label: 'Last hour', ms: HOUR },
  { id: '6h', label: 'Last 6 hours', ms: 6 * HOUR },
  { id: '24h', label: 'Last 24 hours', ms: 24 * HOUR },
  { id: '7d', label: 'Last 7 days', ms: 7 * 24 * HOUR },
];

export type RangeSelection =
  | { readonly kind: 'preset'; readonly id: RangePresetId }
  | { readonly kind: 'custom'; readonly fromMs: number; readonly toMs: number };

export interface RangeWindow {
  readonly fromMs: number;
  readonly toMs: number;
  readonly stepSeconds: number;
}

/** Most buckets a query for this range and step can return: epoch aligned, so the first may start before `from`. */
export function bucketCount(rangeMs: number, stepSeconds: number): number {
  return Math.ceil(rangeMs / (stepSeconds * 1000)) + 1;
}

/**
 * The smallest API step that keeps the range at or under TARGET_BUCKETS, and in every case
 * under MAX_BUCKETS. Null when the range is empty, reversed or longer than the API allows.
 */
export function chooseStepSeconds(rangeMs: number): number | null {
  if (!(rangeMs > 0) || rangeMs > MAX_RANGE_DAYS * 86_400_000) {
    return null;
  }
  const fitting = STEP_SECONDS.find((step) => bucketCount(rangeMs, step) <= TARGET_BUCKETS);
  const step = fitting ?? STEP_SECONDS[STEP_SECONDS.length - 1];
  return bucketCount(rangeMs, step) <= MAX_BUCKETS ? step : null;
}

/** Resolves a selection against `nowMs`. Null when the selection cannot be queried. */
export function resolveWindow(selection: RangeSelection, nowMs: number): RangeWindow | null {
  let fromMs: number;
  let toMs: number;
  if (selection.kind === 'preset') {
    const preset = RANGE_PRESETS.find((item) => item.id === selection.id);
    if (preset === undefined) {
      return null;
    }
    toMs = nowMs;
    fromMs = nowMs - preset.ms;
  } else {
    ({ fromMs, toMs } = selection);
  }
  const stepSeconds = chooseStepSeconds(toMs - fromMs);
  return stepSeconds === null ? null : { fromMs, toMs, stepSeconds };
}

export function formatStep(stepSeconds: number): string {
  if (stepSeconds % 86_400 === 0) {
    const days = stepSeconds / 86_400;
    return days === 1 ? '1 day' : `${days} days`;
  }
  if (stepSeconds % 3600 === 0) {
    const hours = stepSeconds / 3600;
    return hours === 1 ? '1 hour' : `${hours} hours`;
  }
  if (stepSeconds % 60 === 0) {
    const minutes = stepSeconds / 60;
    return minutes === 1 ? '1 minute' : `${minutes} minutes`;
  }
  return stepSeconds === 1 ? '1 second' : `${stepSeconds} seconds`;
}

/** ISO 8601 duration for the API's `step` parameter, in whole seconds. */
export function isoDuration(stepSeconds: number): string {
  return `PT${stepSeconds}S`;
}
