import { IconName } from '../shared/icon/icon.component';

export interface NavItem {
  readonly label: string;
  readonly icon: IconName;
  readonly link: string;
  /** Route prefixes that keep this item marked as the current page. */
  readonly matches: readonly string[];
}

/**
 * Only destinations that exist are listed. Service and metric pages belong to Overview; the alert
 * rules and the alert history share one item.
 */
export const NAV_ITEMS: readonly NavItem[] = [
  {
    label: 'Overview',
    icon: 'overview',
    link: '/app/overview',
    matches: ['/app/overview', '/app/services', '/app/metrics'],
  },
  { label: 'Anomalies', icon: 'anomalies', link: '/app/anomalies', matches: ['/app/anomalies'] },
  { label: 'Alert rules', icon: 'rules', link: '/app/alerts', matches: ['/app/alerts'] },
];
