import { Severity } from '../../shared/signal/signal.model';

/** Resolved colours (and the font) a chart needs, read from the design tokens. */
export interface ChartTokens {
  readonly ink: string;
  readonly ink2: string;
  readonly ink3: string;
  readonly surface: string;
  readonly surfaceRaised: string;
  readonly line: string;
  readonly grid: string;
  readonly axis: string;
  readonly series: string;
  readonly severity: Readonly<Record<Severity, string>>;
  readonly font: string;
}

const COLOR_TOKENS = {
  ink: '--pb-ink',
  ink2: '--pb-ink-2',
  ink3: '--pb-ink-3',
  surface: '--pb-surface',
  surfaceRaised: '--pb-surface-raised',
  line: '--pb-line',
  grid: '--pb-chart-grid',
  axis: '--pb-chart-axis',
  series: '--pb-series-1',
  low: '--pb-severity-low',
  medium: '--pb-severity-medium',
  high: '--pb-severity-high',
} as const;

/**
 * Reads the tokens as the browser resolves them right now. Tokens are declared with light-dark(),
 * and a custom property read back with getPropertyValue is the unresolved declaration, so each one is
 * applied to a probe element's `color` and the computed (resolved, current theme) value is read.
 * Call it again after a theme change.
 */
export function readChartTokens(host: Element): ChartTokens {
  const probe = host.ownerDocument.createElement('span');
  probe.style.display = 'none';
  host.appendChild(probe);
  try {
    const resolved = {} as Record<keyof typeof COLOR_TOKENS, string>;
    for (const [key, token] of Object.entries(COLOR_TOKENS) as [
      keyof typeof COLOR_TOKENS,
      string,
    ][]) {
      probe.style.color = '';
      probe.style.color = `var(${token})`;
      resolved[key] = getComputedStyle(probe).color;
    }
    const font = getComputedStyle(host).fontFamily;
    return {
      ink: resolved.ink,
      ink2: resolved.ink2,
      ink3: resolved.ink3,
      surface: resolved.surface,
      surfaceRaised: resolved.surfaceRaised,
      line: resolved.line,
      grid: resolved.grid,
      axis: resolved.axis,
      series: resolved.series,
      severity: { LOW: resolved.low, MEDIUM: resolved.medium, HIGH: resolved.high },
      font,
    };
  } finally {
    probe.remove();
  }
}

/** `rgb(r, g, b)` or `#rrggbb` with an alpha; anything else is returned unchanged. */
export function withAlpha(color: string, alpha: number): string {
  const rgb = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/.exec(color);
  if (rgb) {
    return `rgba(${rgb[1]}, ${rgb[2]}, ${rgb[3]}, ${alpha})`;
  }
  const hex = /^#([0-9a-f]{6})$/i.exec(color);
  if (hex) {
    const value = parseInt(hex[1], 16);
    return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${alpha})`;
  }
  return color;
}
