import { MetricPoint } from '../../api/models';
import { anomaly } from '../anomalies/anomaly-testing';
import {
  ChartInput,
  SEVERITY_SYMBOL,
  anomalyTooltip,
  buildChartOption,
  chartLabel,
  countGaps,
  fillGaps,
  placeMarkers,
  summarizeSeries,
} from './chart-options';
import { ChartTokens, withAlpha } from './chart-tokens';

const T0 = Date.parse('2026-05-01T12:00:00Z');
const STEP = 60;

function tokens(prefix: string): ChartTokens {
  return {
    ink: `${prefix}-ink`,
    ink2: `${prefix}-ink2`,
    ink3: `${prefix}-ink3`,
    surface: `${prefix}-surface`,
    surfaceRaised: `${prefix}-raised`,
    line: `${prefix}-line`,
    grid: `${prefix}-grid`,
    axis: `${prefix}-axis`,
    series: `${prefix}-series`,
    severity: { LOW: `${prefix}-low`, MEDIUM: `${prefix}-medium`, HIGH: `${prefix}-high` },
    font: `${prefix}-font`,
  };
}

function points(values: (number | null)[]): MetricPoint[] {
  return values.map((value, index) => ({ t: T0 + index * STEP * 1000, value, count: 3 }));
}

function input(overrides: Partial<ChartInput> = {}): ChartInput {
  return {
    points: points([10, 11, 12, 40, 12]),
    anomalies: [],
    fromMs: T0,
    toMs: T0 + 5 * STEP * 1000,
    stepSeconds: STEP,
    unit: 'ms',
    aggregation: 'AVG',
    tokens: tokens('light'),
    reducedMotion: false,
    ...overrides,
  };
}

interface Option {
  animation: boolean;
  series: {
    id: string;
    data: unknown[];
    markArea?: { data: unknown[] };
    endLabel?: { show: boolean; formatter?: () => string };
    lineStyle?: { color: string };
  }[];
  yAxis: { name: string };
  tooltip: { formatter: (params: unknown) => string };
}

const build = (overrides: Partial<ChartInput> = {}) =>
  buildChartOption(input(overrides)) as unknown as Option;

describe('placeMarkers', () => {
  it('puts the ring on the bucket that contains the start of the window', () => {
    const hit = anomaly({ windowStart: new Date(T0 + 3 * STEP * 1000 + 20_000).toISOString() });
    const [marker] = placeMarkers(points([10, 11, 12, 40, 12]), [hit], STEP);
    expect(marker).toMatchObject({ x: T0 + 3 * STEP * 1000, y: 40 });
  });

  it('uses the value the detector reported when the bucket has a gap', () => {
    const hit = anomaly({
      windowStart: new Date(T0 + 2 * STEP * 1000).toISOString(),
      details: { value: 99.5 },
    });
    const [marker] = placeMarkers(points([10, 11, null, 40]), [hit], STEP);
    expect(marker).toMatchObject({ y: 99.5 });
  });

  it('draws nothing it cannot place, instead of inventing a position', () => {
    const outside = anomaly({ windowStart: new Date(T0 + 50 * STEP * 1000).toISOString() });
    const gap = anomaly({ windowStart: new Date(T0 + 2 * STEP * 1000).toISOString() });
    expect(placeMarkers(points([10, 11, null, 40]), [outside, gap], STEP)).toEqual([]);
    expect(placeMarkers([], [gap], STEP)).toEqual([]);
  });
});

describe('summarizeSeries', () => {
  it('finds min, max and the last value that exists', () => {
    expect(summarizeSeries(points([5, 9, 2, null]))).toEqual({
      count: 4,
      min: 2,
      max: 9,
      last: 2,
      lastAt: T0 + 2 * STEP * 1000,
    });
  });

  it('is empty for no data', () => {
    expect(summarizeSeries([])).toEqual({
      count: 0,
      min: null,
      max: null,
      last: null,
      lastAt: null,
    });
    expect(summarizeSeries(points([null, null])).min).toBeNull();
  });
});

describe('buildChartOption', () => {
  it('takes every colour from the tokens, none from the code', () => {
    const hit = anomaly({
      severity: 'HIGH',
      windowStart: new Date(T0 + 3 * STEP * 1000).toISOString(),
      windowEnd: new Date(T0 + 4 * STEP * 1000).toISOString(),
    });
    const light = JSON.stringify(
      buildChartOption(input({ anomalies: [hit], tokens: tokens('light') })),
    );
    const dark = JSON.stringify(
      buildChartOption(input({ anomalies: [hit], tokens: tokens('dark') })),
    );

    expect(light).toContain('light-series');
    expect(light).toContain('light-high');
    expect(light).toContain('light-grid');
    expect(light).not.toContain('dark-');
    // Rebuilt for the other theme, the option has the other colours and nothing else changed.
    expect(dark.replaceAll('dark-', 'light-')).toBe(light);
    expect(light).not.toMatch(/#[0-9a-f]{3,8}\b/i);
  });

  it('draws the observed series and one ring per placed anomaly', () => {
    const hit = anomaly({ windowStart: new Date(T0 + 3 * STEP * 1000).toISOString() });
    const option = build({ anomalies: [hit] });
    const observed = option.series.find((s) => s.id === 'observed');
    const rings = option.series.find((s) => s.id === 'anomalies');
    expect(observed?.data).toHaveLength(5);
    expect(rings?.data).toHaveLength(1);
  });

  it.each(['LOW', 'MEDIUM', 'HIGH'] as const)(
    'gives %s its own ring colour and shape, so colour is not the only cue',
    (severity) => {
      const hit = anomaly({ severity, windowStart: new Date(T0 + 3 * STEP * 1000).toISOString() });
      const rings = build({ anomalies: [hit] }).series.find((s) => s.id === 'anomalies');
      const [ring] = rings?.data as {
        symbol: string;
        symbolSize: number;
        itemStyle: { borderColor: string };
      }[];
      expect(ring.symbol).toBe(SEVERITY_SYMBOL[severity].symbol);
      expect(ring.symbolSize).toBe(SEVERITY_SYMBOL[severity].size);
      expect(ring.itemStyle.borderColor).toBe(`light-${severity.toLowerCase()}`);
    },
  );

  it('uses a different shape for every severity', () => {
    const shapes = Object.values(SEVERITY_SYMBOL).map((s) => s.symbol);
    expect(new Set(shapes).size).toBe(3);
    const sizes = Object.values(SEVERITY_SYMBOL).map((s) => s.size);
    expect(sizes).toEqual([...sizes].sort((a, b) => a - b));
  });

  it('shades only windows that last longer than one instant', () => {
    const instant = anomaly({ windowStart: new Date(T0 + 1 * STEP * 1000).toISOString() });
    const sustained = anomaly({
      severity: 'HIGH',
      windowStart: new Date(T0 + 2 * STEP * 1000).toISOString(),
      windowEnd: new Date(T0 + 4 * STEP * 1000).toISOString(),
    });
    const area = build({ anomalies: [instant, sustained] }).series.find(
      (s) => s.id === 'observed',
    )?.markArea;
    expect(area?.data).toHaveLength(1);
  });

  it('does not invent an expected range', () => {
    const serialised = JSON.stringify(buildChartOption(input({ anomalies: [anomaly()] })));
    expect(serialised).not.toMatch(/expected|band|upper|lower/i);
  });

  it('labels the last value on the line, with its unit', () => {
    const label = build().series[0].endLabel;
    expect(label?.show).toBe(true);
    expect(label?.formatter?.()).toBe('12 ms');
  });

  it('names the aggregation and unit on the axis', () => {
    expect(build().yAxis.name).toBe('Average (ms)');
    expect(build({ unit: '', aggregation: 'P95' }).yAxis.name).toBe('95th percentile');
  });

  it('turns animation off for reduced motion', () => {
    expect(build({ reducedMotion: true }).animation).toBe(false);
    expect(build({ reducedMotion: false }).animation).toBe(true);
  });

  it('builds a valid option for an empty series without a direct label', () => {
    const option = build({ points: [], anomalies: [anomaly()] });
    expect(option.series[0].data).toEqual([]);
    expect(option.series[0].endLabel?.show).toBe(false);
    expect(option.series[1].data).toEqual([]);
  });

  it('breaks the line at a bucket without a value instead of drawing through it', () => {
    const data = build({ points: points([1, null, 3]) }).series[0].data as [
      number,
      number | null,
    ][];
    expect(data[1][1]).toBeNull();
  });

  describe('tooltip', () => {
    it('lists the value and every anomaly under the pointer', () => {
      const hit = anomaly({
        severity: 'HIGH',
        detector: 'ZSCORE',
        score: 0.9123,
        windowStart: new Date(T0 + 3 * STEP * 1000).toISOString(),
        details: { mean: 12, std: 1.5 },
      });
      const option = build({ anomalies: [hit] });
      const html = option.tooltip.formatter([
        { seriesType: 'line', value: [T0 + 3 * STEP * 1000, 40] },
        { seriesType: 'scatter', data: { anomalyId: hit.id } },
      ]);
      expect(html).toContain('40 ms');
      expect(html).toContain('High');
      expect(html).toContain('Z-score');
      expect(html).toContain('0.91');
      expect(html).toContain('Expected 12 ms');
      expect(html).toContain('1.5');
    });

    it('carries no inline style, which the content security policy would block', () => {
      const hit = anomaly();
      expect(anomalyTooltip(hit, 'ms')).not.toMatch(/style\s*=/i);
    });

    it('escapes anything that came from the server', () => {
      const hit = anomaly({ detector: '<img src=x onerror=alert(1)>' as never });
      const html = anomalyTooltip(hit, '');
      expect(html).not.toContain('<img');
      expect(html).toContain('&#60;img');
    });
  });
});

describe('chartLabel', () => {
  const base = {
    name: 'latency.p95',
    unit: 'ms',
    aggregation: 'AVG' as const,
    fromMs: T0,
    toMs: T0 + 300_000,
  };

  it('states min, max, latest and the anomaly count', () => {
    const text = chartLabel({ ...base, points: points([10, 40, 12]), anomalyCount: 2 });
    expect(text).toContain('latency.p95');
    expect(text).toContain('average');
    expect(text).toContain('Minimum 10 ms');
    expect(text).toContain('maximum 40 ms');
    expect(text).toContain('latest 12 ms');
    expect(text).toContain('2 anomalies marked');
  });

  it('says so when there are none, and when there is no data', () => {
    expect(chartLabel({ ...base, points: points([1]), anomalyCount: 0 })).toContain(
      'No anomalies.',
    );
    expect(chartLabel({ ...base, points: points([1]), anomalyCount: 1 })).toContain(
      '1 anomaly marked',
    );
    expect(chartLabel({ ...base, points: [], anomalyCount: 0 })).toContain(
      'No values in this range',
    );
  });
});

describe('withAlpha', () => {
  it.each([
    ['rgb(10, 20, 30)', 'rgba(10, 20, 30, 0.5)'],
    ['rgba(10 20 30 / 1)', 'rgba(10, 20, 30, 0.5)'],
    ['#0a141e', 'rgba(10, 20, 30, 0.5)'],
    ['not-a-colour', 'not-a-colour'],
  ])('%s', (color, expected) => {
    expect(withAlpha(color, 0.5)).toBe(expected);
  });
});

describe('gaps in the series', () => {
  const HOUR = 3_600_000;
  const sparse: MetricPoint[] = [
    { t: T0, value: 10, count: 3 },
    { t: T0 + HOUR, value: 12, count: 3 },
  ];

  it('fills the missing buckets with nulls so the line breaks between 12:00 and 13:00', () => {
    const option = build({ points: sparse, fromMs: T0, toMs: T0 + HOUR + 60_000 });
    const data = option.series[0].data as [number, number | null][];

    expect(data[0]).toEqual([T0, 10]);
    expect(data[data.length - 1]).toEqual([T0 + HOUR, 12]);
    const between = data.slice(1, -1);
    expect(between).toHaveLength(59);
    expect(between.every(([, value]) => value === null)).toBe(true);
    // No two neighbours are both real values, so no continuous segment joins the two ends.
    const joined = data.some((row, i) => i > 0 && row[1] !== null && data[i - 1][1] !== null);
    expect(joined).toBe(false);
    expect((option.series[0] as unknown as { connectNulls: boolean }).connectNulls).toBe(false);
  });

  it('adds nothing when the buckets are contiguous, and never more than the API maximum', () => {
    expect(fillGaps(points([1, 2, 3]), STEP)).toHaveLength(3);
    const far: MetricPoint[] = [
      { t: T0, value: 1, count: 1 },
      { t: T0 + 100_000 * 60_000, value: 2, count: 1 },
    ];
    expect(fillGaps(far, STEP).length).toBeLessThanOrEqual(2002);
  });

  it('keeps the accessible summary free of invented buckets and mentions the gap', () => {
    const label = chartLabel({
      name: 'latency',
      unit: 'ms',
      aggregation: 'AVG',
      points: sparse,
      anomalyCount: 0,
      stepSeconds: STEP,
      fromMs: T0,
      toMs: T0 + HOUR,
    });
    expect(label).toContain('2 points');
    expect(label).toContain('1 gap where no data arrived');
    expect(countGaps(sparse, STEP)).toBe(1);
    expect(countGaps(points([1, 2, 3]), STEP)).toBe(0);
  });
});
