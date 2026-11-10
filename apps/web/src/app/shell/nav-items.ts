import { IconName } from '../shared/icon/icon.component';

export interface NavItem {
  readonly label: string;
  readonly icon: IconName;
  readonly link: string;
  /** Route prefixes that keep this item marked as the current page. */
  readonly matches: readonly string[];
}

/**
 * Only destinations that exist are listed. Alert rules join the rail with their own route.
 * Service and metric pages belong to Overview.
 */
export const NAV_ITEMS: readonly NavItem[] = [
  {
    label: 'Overview',
    icon: 'overview',
    link: '/app/overview',
    matches: ['/app/overview', '/app/services', '/app/metrics'],
  },
  { label: 'Anomalies', icon: 'anomalies', link: '/app/anomalies', matches: ['/app/anomalies'] },
];
