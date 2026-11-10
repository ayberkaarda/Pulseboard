import {
  MAX_BUCKETS,
  MAX_RANGE_DAYS,
  RANGE_PRESETS,
  STEP_SECONDS,
  TARGET_BUCKETS,
  bucketCount,
  chooseStepSeconds,
  formatStep,
  isoDuration,
  resolveWindow,
} from './range';

const DAY = 86_400_000;
const NOW = Date.parse('2026-05-01T12:00:00Z');

describe('chooseStepSeconds', () => {
  it.each(RANGE_PRESETS.map((preset) => [preset.id, preset.ms] as const))(
    'the %s preset stays within the target and the API limit',
    (_id, ms) => {
      const step = chooseStepSeconds(ms);
      expect(step).not.toBeNull();
      expect(bucketCount(ms, step as number)).toBeLessThanOrEqual(TARGET_BUCKETS);
      expect(bucketCount(ms, step as number)).toBeLessThanOrEqual(MAX_BUCKETS);
    },
  );

  it.each([
    ['1h', 3_600_000, 10],
    ['6h', 6 * 3_600_000, 60],
    ['24h', DAY, 300],
    ['7d', 7 * DAY, 1800],
  ])('picks the expected step for %s', (_name, ms, step) => {
    expect(chooseStepSeconds(ms)).toBe(step);
  });

  it('is the smallest fitting step, so detail is not thrown away', () => {
    for (const ms of [1000, 90_000, 3_600_000, 5 * 3_600_000, 2 * DAY, 40 * DAY]) {
      const step = chooseStepSeconds(ms) as number;
      const smaller = STEP_SECONDS[STEP_SECONDS.indexOf(step) - 1];
      if (smaller !== undefined) {
        expect(bucketCount(ms, smaller)).toBeGreaterThan(TARGET_BUCKETS);
      }
    }
  });

  it('never exceeds 2000 buckets for any allowed range, scanning the whole span', () => {
    for (let ms = 1000; ms <= MAX_RANGE_DAYS * DAY; ms = Math.ceil(ms * 1.37)) {
      const step = chooseStepSeconds(ms);
      expect(step).not.toBeNull();
      expect(STEP_SECONDS).toContain(step);
      expect(bucketCount(ms, step as number)).toBeLessThanOrEqual(MAX_BUCKETS);
    }
    const longest = chooseStepSeconds(MAX_RANGE_DAYS * DAY) as number;
    expect(bucketCount(MAX_RANGE_DAYS * DAY, longest)).toBeLessThanOrEqual(MAX_BUCKETS);
  });

  it.each([
    ['empty', 0],
    ['reversed', -1000],
    ['not a number', Number.NaN],
    ['longer than the API allows', (MAX_RANGE_DAYS + 1) * DAY],
  ])('refuses a range that is %s', (_name, ms) => {
    expect(chooseStepSeconds(ms)).toBeNull();
  });
});

describe('resolveWindow', () => {
  it('ends a preset at now and starts its length earlier', () => {
    expect(resolveWindow({ kind: 'preset', id: '6h' }, NOW)).toEqual({
      fromMs: NOW - 6 * 3_600_000,
      toMs: NOW,
      stepSeconds: 60,
    });
  });

  it('passes a custom range through with a fitting step', () => {
    const window = resolveWindow(
      { kind: 'custom', fromMs: NOW - 30 * DAY, toMs: NOW - 10 * DAY },
      NOW,
    );
    expect(window).toMatchObject({ fromMs: NOW - 30 * DAY, toMs: NOW - 10 * DAY });
    expect(bucketCount(20 * DAY, window?.stepSeconds as number)).toBeLessThanOrEqual(MAX_BUCKETS);
  });

  it.each([
    [NOW, NOW],
    [NOW, NOW - 1],
    [NOW - 400 * DAY, NOW],
  ])('is null for the custom range %i to %i', (fromMs, toMs) => {
    expect(resolveWindow({ kind: 'custom', fromMs, toMs }, NOW)).toBeNull();
  });
});

describe('formatting', () => {
  it.each([
    [1, '1 second'],
    [10, '10 seconds'],
    [60, '1 minute'],
    [300, '5 minutes'],
    [3600, '1 hour'],
    [7200, '2 hours'],
    [86_400, '1 day'],
    [172_800, '2 days'],
  ])('formats %i seconds as %s', (seconds, text) => {
    expect(formatStep(seconds)).toBe(text);
  });

  it('writes the step as an ISO 8601 duration in whole seconds', () => {
    expect(isoDuration(300)).toBe('PT300S');
  });
});
