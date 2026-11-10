import { IconName } from '../shared/icon/icon.component';

export type SectionId = 'overview' | 'services' | 'anomalies' | 'rules';

export interface ShellSection {
  readonly id: SectionId;
  readonly label: string;
  readonly icon: IconName;
  readonly summary: string;
  readonly emptyTitle: string;
  readonly emptyBody: string;
}

export const SHELL_SECTIONS: readonly ShellSection[] = [
  {
    id: 'overview',
    label: 'Overview',
    icon: 'overview',
    summary: 'Every service on one board, sorted by what needs attention first.',
    emptyTitle: 'No services reporting yet',
    emptyBody:
      'Services appear here after their first heartbeat. Each tile shows health, latency and open anomalies.',
  },
  {
    id: 'services',
    label: 'Services',
    icon: 'services',
    summary: 'Live metrics for a single service, drawn against their expected range.',
    emptyTitle: 'No services registered',
    emptyBody: 'Create an API key for a service, then send metrics to start its timeline.',
  },
  {
    id: 'anomalies',
    label: 'Anomalies',
    icon: 'anomalies',
    summary: 'Every moment a metric left its expected range, newest first.',
    emptyTitle: 'No anomalies detected',
    emptyBody: 'When a metric moves outside its expected range, the event streams in here live.',
  },
  {
    id: 'rules',
    label: 'Alert rules',
    icon: 'rules',
    summary: 'Decide which anomalies notify someone, and how urgently.',
    emptyTitle: 'No alert rules yet',
    emptyBody: 'A rule pairs a metric and a severity with the people who should hear about it.',
  },
];
