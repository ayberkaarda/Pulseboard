import { format, formatDistanceStrict } from 'date-fns';

/** Absolute local time with seconds, for titles, tooltips and tables. */
export function absoluteTime(ms: number): string {
  return format(ms, 'MMM d, yyyy HH:mm:ss');
}

/** "3 minutes ago" / "in 2 minutes"; seconds are not shown below a minute. */
export function relativeTime(ms: number, nowMs: number): string {
  if (Math.abs(nowMs - ms) < 45_000) {
    return ms > nowMs ? 'in a moment' : 'just now';
  }
  return formatDistanceStrict(ms, nowMs, { addSuffix: true });
}

export function formatValue(value: number): string {
  const magnitude = Math.abs(value);
  const digits = magnitude >= 1000 ? 0 : magnitude >= 100 ? 1 : magnitude >= 1 ? 2 : 4;
  return new Intl.NumberFormat('en', { maximumFractionDigits: digits }).format(value);
}
