import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { SEVERITY_RANK, Severity } from './signal.model';

/**
 * Severity as three ascending bars, filled up to the level. Count is the
 * primary channel; the flare ramp (light to intense) reinforces it.
 */
@Component({
  selector: 'app-severity-glyph',
  template: `
    <svg viewBox="0 0 14 12" aria-hidden="true" focusable="false">
      @for (bar of bars; track bar.index) {
        <rect
          [attr.x]="bar.x"
          [attr.y]="bar.y"
          width="3"
          [attr.height]="12 - bar.y"
          rx="1"
          [class.on]="bar.index <= rank()"
        />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      width: calc(var(--pb-glyph-size, 12px) * 14 / 12);
      height: var(--pb-glyph-size, 12px);
      flex: none;
      color: var(--pb-severity-low);
    }
    :host([data-severity='MEDIUM']) {
      color: var(--pb-severity-medium);
    }
    :host([data-severity='HIGH']) {
      color: var(--pb-severity-high);
    }
    svg {
      width: 100%;
      height: 100%;
    }
    rect {
      fill: var(--pb-line);
    }
    rect.on {
      fill: currentColor;
    }
  `,
  host: { '[attr.data-severity]': 'severity()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeverityGlyphComponent {
  readonly severity = input.required<Severity>();

  protected readonly rank = computed(() => SEVERITY_RANK[this.severity()]);

  protected readonly bars = [
    { index: 1, x: 0, y: 7 },
    { index: 2, x: 5.5, y: 3.5 },
    { index: 3, x: 11, y: 0 },
  ] as const;
}
