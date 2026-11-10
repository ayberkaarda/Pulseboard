import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';

import { IconComponent } from '../../shared/icon/icon.component';
import { HealthGlyphComponent } from '../../shared/signal/health-glyph.component';
import { SeverityGlyphComponent } from '../../shared/signal/severity-glyph.component';
import {
  HEALTH_LABELS,
  HealthState,
  SEVERITY_LABELS,
  Severity,
} from '../../shared/signal/signal.model';

interface KeyEntry<T> {
  readonly value: T;
  readonly label: string;
  readonly meaning: string;
}

/** Explains how Pulseboard encodes health, severity and chart marks. */
@Component({
  selector: 'app-signal-key-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    IconComponent,
    HealthGlyphComponent,
    SeverityGlyphComponent,
  ],
  templateUrl: './signal-key-dialog.component.html',
  styleUrl: './signal-key-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignalKeyDialogComponent {
  protected readonly health: readonly KeyEntry<HealthState>[] = [
    {
      value: 'healthy',
      label: HEALTH_LABELS.healthy,
      meaning: 'Responding within its latency and error objectives.',
    },
    {
      value: 'degraded',
      label: HEALTH_LABELS.degraded,
      meaning: 'Responding, but latency or errors are above objective.',
    },
    {
      value: 'down',
      label: HEALTH_LABELS.down,
      meaning: 'Not responding, or failing most requests.',
    },
    {
      value: 'unknown',
      label: HEALTH_LABELS.unknown,
      meaning: 'No heartbeat received recently.',
    },
  ];

  protected readonly severity: readonly KeyEntry<Severity>[] = [
    {
      value: 'LOW',
      label: SEVERITY_LABELS.LOW,
      meaning: 'Briefly outside the expected range. Worth a look.',
    },
    {
      value: 'MEDIUM',
      label: SEVERITY_LABELS.MEDIUM,
      meaning: 'A sustained deviation that users may notice.',
    },
    {
      value: 'HIGH',
      label: SEVERITY_LABELS.HIGH,
      meaning: 'A severe deviation. Someone should act now.',
    },
  ];
}
