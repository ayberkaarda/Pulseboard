import type { EChartsCoreOption } from 'echarts/core';

import { Aggregation, Anomaly, MetricPoint } from '../../api/models';
import { absoluteTime, formatValue } from '../../shared/format/format-time';
import {
  AGGREGATION_LABELS,
  DETECTOR_LABELS,
  SEVERITY_LABELS,
  Severity,
} from '../../shared/signal/signal.model';
import { ChartTokens, withAlpha } from './chart-tokens';

/**
 * Severity is drawn as a ring whose shape and size change with the level (circle, diamond, triangle,
 * 9 to 13px), in addition to the severity colour, so the level survives colour-vision differences.
 */
export const SEVERITY_SYMBOL: Readonly<Record<Severity, { symbol: string; size: number }>> = {
  LOW: { symbol: 'circle', size: 10 },
  MEDIUM: { symbol: 'diamond', size: 12 },
  HIGH: { symbol: 'triangle', size: 14 },
};

export interface ChartInput {
  readonly points: readonly MetricPoint[];
  readonly anomalies: readonly Anomaly[];
  readonly fromMs: number;
  readonly toMs: number;
  readonly stepSeconds: number;
  readonly unit: string;
  readonly aggregation: Aggregation | null;
  readonly tokens: ChartTokens;
  readonly reducedMotion: boolean;
}

export interface Marker {
  readonly anomaly: Anomaly;
  /** Where the ring sits: the bucket that contains the start of the anomaly window. */
  readonly x: number;
  readonly y: number;
}

/**
 * Puts each anomaly on the series. The ring goes on the bucket that contains the start of the
 * window; when that bucket has no value (a gap), the raw value the detector reported is used if it
 * has one; otherwise the anomaly gets no ring (it is still listed below the chart).
 */
export function placeMarkers(
  points: readonly MetricPoint[],
  anomalies: readonly Anomaly[],
  stepSeconds: number,
): Marker[] {
  const stepMs = stepSeconds * 1000;
  const markers: Marker[] = [];
  for (const anomaly of anomalies) {
    const bucket = bucketAtOrBefore(points, anomaly.startMs);
    if (bucket !== null && anomaly.startMs - bucket.t < stepMs && bucket.value !== null) {
      markers.push({ anomaly, x: bucket.t, y: bucket.value });
      continue;
    }
    const raw = anomaly.details['value'];
    if (typeof raw === 'number' && Number.isFinite(raw)) {
      markers.push({ anomaly, x: anomaly.startMs, y: raw });
    }
  }
  return markers;
}

function bucketAtOrBefore(points: readonly MetricPoint[], t: number): MetricPoint | null {
  let low = 0;
  let high = points.length - 1;
  let found: MetricPoint | null = null;
  while (low <= high) {
    const middle = (low + high) >> 1;
    if (points[middle].t <= t) {
      found = points[middle];
      low = middle + 1;
    } else {
      high = middle - 1;
    }
  }
  return found;
}

export interface SeriesSummary {
  readonly count: number;
  readonly min: number | null;
  readonly max: number | null;
  readonly last: number | null;
  readonly lastAt: number | null;
}

export function summarizeSeries(points: readonly MetricPoint[]): SeriesSummary {
  let min: number | null = null;
  let max: number | null = null;
  let last: number | null = null;
  let lastAt: number | null = null;
  for (const point of points) {
    if (point.value === null) {
      continue;
    }
    min = min === null ? point.value : Math.min(min, point.value);
    max = max === null ? point.value : Math.max(max, point.value);
    last = point.value;
    lastAt = point.t;
  }
  return { count: points.length, min, max, last, lastAt };
}

/** Buckets filled in at most; the API itself never returns more than 2000. */
const MAX_FILLED_BUCKETS = 2000;

/**
 * The API returns only buckets that hold samples. For drawing, the buckets missing between two real
 * ones are added as null values, so a line with connectNulls off breaks where nothing was measured
 * instead of joining the two ends. Only the chart gets these; the table and the summary use the
 * real buckets. At most MAX_FILLED_BUCKETS rows are added.
 */
export function fillGaps(
  points: readonly MetricPoint[],
  stepSeconds: number,
): readonly { t: number; value: number | null }[] {
  const stepMs = stepSeconds * 1000;
  const filled: { t: number; value: number | null }[] = [];
  let added = 0;
  points.forEach((point, index) => {
    if (index > 0 && stepMs > 0) {
      for (
        let t = points[index - 1].t + stepMs;
        t < point.t && added < MAX_FILLED_BUCKETS;
        t += stepMs
      ) {
        filled.push({ t, value: null });
        added += 1;
      }
    }
    filled.push({ t: point.t, value: point.value });
  });
  return filled;
}

/** How many separate stretches without any bucket lie between the first and last real bucket. */
export function countGaps(points: readonly MetricPoint[], stepSeconds: number): number {
  const stepMs = stepSeconds * 1000;
  let gaps = 0;
  for (let index = 1; index < points.length; index += 1) {
    if (points[index].t - points[index - 1].t > stepMs) {
      gaps += 1;
    }
  }
  return gaps;
}

function withUnit(value: number | null, unit: string): string {
  return value === null ? 'no value' : `${formatValue(value)}${unit ? ` ${unit}` : ''}`;
}

/** The text alternative of the chart: what it shows, in numbers. */
export function chartLabel(input: {
  readonly name: string;
  readonly unit: string;
  readonly aggregation: Aggregation | null;
  readonly points: readonly MetricPoint[];
  readonly anomalyCount: number;
  readonly stepSeconds?: number;
  readonly fromMs: number;
  readonly toMs: number;
}): string {
  const summary = summarizeSeries(input.points);
  const aggregation = input.aggregation ? AGGREGATION_LABELS[input.aggregation].toLowerCase() : '';
  const head = `Line chart of ${input.name}${aggregation ? `, ${aggregation}` : ''}${input.unit ? ` in ${input.unit}` : ''}, from ${absoluteTime(input.fromMs)} to ${absoluteTime(input.toMs)}.`;
  if (summary.min === null) {
    return `${head} No values in this range.`;
  }
  const anomalies =
    input.anomalyCount === 0
      ? 'No anomalies.'
      : `${input.anomalyCount} ${input.anomalyCount === 1 ? 'anomaly' : 'anomalies'} marked.`;
  const gaps = input.stepSeconds === undefined ? 0 : countGaps(input.points, input.stepSeconds);
  const gapText =
    gaps === 0 ? '' : ` ${gaps} ${gaps === 1 ? 'gap' : 'gaps'} where no data arrived.`;
  return `${head} ${summary.count} points.${gapText} Minimum ${withUnit(summary.min, input.unit)}, maximum ${withUnit(summary.max, input.unit)}, latest ${withUnit(summary.last, input.unit)}. ${anomalies}`;
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (char) => `&#${char.charCodeAt(0)};`);
}

function expectedText(details: Readonly<Record<string, unknown>>, unit: string): string | null {
  const mean = details['mean'];
  const std = details['std'];
  if (typeof mean !== 'number' || typeof std !== 'number' || !Number.isFinite(mean + std)) {
    return null;
  }
  return `Expected ${withUnit(mean, unit)} ± ${formatValue(std)}`;
}

/** Tooltip body for an anomaly. Text only, no inline styles (the page's CSP forbids them). */
export function anomalyTooltip(anomaly: Anomaly, unit: string): string {
  const expected = expectedText(anomaly.details, unit);
  const sustained = anomaly.endMs > anomaly.startMs;
  const rows: [string, string][] = [
    ['Metric', anomaly.metricName],
    ['Service', anomaly.serviceName],
    ['Severity', SEVERITY_LABELS[anomaly.severity]],
    ['Detector', DETECTOR_LABELS[anomaly.detector] ?? anomaly.detector],
    ['Score', anomaly.score.toFixed(2)],
    [
      'Window',
      sustained
        ? `${absoluteTime(anomaly.startMs)} to ${absoluteTime(anomaly.endMs)}`
        : absoluteTime(anomaly.startMs),
    ],
    ['Status', anomaly.acknowledgedAt === null ? 'Open' : 'Acknowledged'],
  ];
  const body = rows
    .map(
      ([key, value]) =>
        `<div class="pb-tt-row"><span>${key}</span><b>${escapeHtml(value)}</b></div>`,
    )
    .join('');
  return `<div class="pb-tt-block"><div class="pb-tt-title">Anomaly</div>${body}${
    expected === null ? '' : `<div class="pb-tt-note">${escapeHtml(expected)}</div>`
  }</div>`;
}

interface TooltipParam {
  readonly seriesType?: string;
  readonly value?: unknown;
  readonly data?: { readonly anomalyId?: string };
}

/**
 * The ECharts option for one metric.
 *
 * - Observed line in series slot 1, the expected-range band is NOT drawn: the API does not provide
 *   one for a series (only the z-score detector reports a mean and spread, and only at its anomalies,
 *   which the tooltip shows), and a band would have to be invented.
 * - Anomalies are hollow rings that differ in shape and size per severity; a sustained window is
 *   also shaded in the severity colour at low alpha.
 * - The last value is labelled directly on the line.
 * - Colours come from `tokens`, so the caller rebuilds the option when the theme changes.
 * - Animation is off for reduced motion.
 */
export function buildChartOption(input: ChartInput): EChartsCoreOption {
  const { tokens, points, unit } = input;
  const markers = placeMarkers(points, input.anomalies, input.stepSeconds);
  const byId = new Map(markers.map((marker) => [marker.anomaly.id, marker.anomaly]));
  const aggregation = input.aggregation ? AGGREGATION_LABELS[input.aggregation] : 'Value';
  const textStyle = { color: tokens.ink3, fontFamily: tokens.font, fontSize: 12 };
  const last = summarizeSeries(points);
  const lineData = fillGaps(points, input.stepSeconds).map((point) => [point.t, point.value]);

  const areas = markers
    .filter(({ anomaly }) => anomaly.endMs > anomaly.startMs)
    .map(({ anomaly }) => [
      {
        xAxis: anomaly.startMs,
        itemStyle: { color: withAlpha(tokens.severity[anomaly.severity], 0.14) },
      },
      { xAxis: anomaly.endMs },
    ]);

  return {
    animation: !input.reducedMotion,
    animationDuration: 320,
    textStyle,
    grid: { left: 8, right: 84, top: 32, bottom: 8, containLabel: true },
    xAxis: {
      type: 'time',
      min: input.fromMs,
      max: input.toMs,
      axisLine: { lineStyle: { color: tokens.axis } },
      axisTick: { lineStyle: { color: tokens.axis } },
      axisLabel: { ...textStyle, hideOverlap: true },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      scale: true,
      name: unit ? `${aggregation} (${unit})` : aggregation,
      nameTextStyle: { ...textStyle, align: 'left' },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { ...textStyle, formatter: (value: number) => formatValue(value) },
      splitLine: { lineStyle: { color: tokens.grid } },
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      className: 'pb-chart-tooltip',
      backgroundColor: tokens.surfaceRaised,
      borderColor: tokens.line,
      borderWidth: 1,
      padding: 10,
      textStyle: { color: tokens.ink, fontFamily: tokens.font, fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: tokens.axis, width: 1 } },
      formatter: (raw: unknown) => {
        const params = (Array.isArray(raw) ? raw : [raw]) as TooltipParam[];
        const line = params.find((param) => param.seriesType === 'line');
        const lineValue = Array.isArray(line?.value) ? (line.value as unknown[]) : null;
        const when = lineValue ? Number(lineValue[0]) : null;
        const head =
          when === null
            ? ''
            : `<div class="pb-tt-time">${escapeHtml(absoluteTime(when))}</div><div class="pb-tt-row"><span>${escapeHtml(aggregation)}</span><b>${escapeHtml(withUnit(typeof lineValue?.[1] === 'number' ? lineValue[1] : null, unit))}</b></div>`;
        const anomalies = params
          .map((param) => (param.seriesType === 'scatter' ? param.data?.anomalyId : undefined))
          .filter((id): id is string => id !== undefined)
          .map((id) => byId.get(id))
          .filter((anomaly): anomaly is Anomaly => anomaly !== undefined)
          .map((anomaly) => anomalyTooltip(anomaly, unit))
          .join('');
        return head + anomalies;
      },
    },
    series: [
      {
        id: 'observed',
        name: aggregation,
        type: 'line',
        data: lineData,
        color: tokens.series,
        lineStyle: { width: 2, color: tokens.series, join: 'round' },
        itemStyle: { color: tokens.series },
        showSymbol: points.length <= 40,
        symbol: 'circle',
        symbolSize: 5,
        connectNulls: false,
        endLabel:
          last.last === null
            ? { show: false }
            : {
                show: true,
                formatter: () => withUnit(last.last, unit),
                color: tokens.ink,
                fontFamily: tokens.font,
                fontSize: 12,
                fontWeight: 600,
              },
        emphasis: { disabled: true },
        markArea: { silent: true, data: areas },
        z: 2,
      },
      {
        id: 'anomalies',
        name: 'Anomalies',
        type: 'scatter',
        z: 5,
        data: markers.map(({ anomaly, x, y }) => ({
          value: [x, y],
          anomalyId: anomaly.id,
          symbol: SEVERITY_SYMBOL[anomaly.severity].symbol,
          symbolSize: SEVERITY_SYMBOL[anomaly.severity].size,
          itemStyle: {
            color: tokens.surface,
            borderColor: tokens.severity[anomaly.severity],
            borderWidth: 2,
            shadowColor: tokens.surface,
            shadowBlur: 0,
          },
        })),
      },
    ],
  };
}
