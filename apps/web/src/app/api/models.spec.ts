import { parseStreamAnomaly } from './models';
import { rawAnomaly } from '../features/anomalies/anomaly-testing';

describe('parseStreamAnomaly', () => {
  it('reads the metric and service names of an event', () => {
    const parsed = parseStreamAnomaly(
      rawAnomaly({ metricName: 'latency.p95', serviceName: 'checkout' }),
    );
    expect(parsed?.metricName).toBe('latency.p95');
    expect(parsed?.serviceName).toBe('checkout');
  });

  it.each(['metricName', 'serviceName'])('rejects an event without a usable %s', (field) => {
    const base = rawAnomaly() as unknown as Record<string, unknown>;
    expect(parseStreamAnomaly({ ...base, [field]: undefined })).toBeNull();
    expect(parseStreamAnomaly({ ...base, [field]: '' })).toBeNull();
    expect(parseStreamAnomaly({ ...base, [field]: 7 })).toBeNull();
  });
});
