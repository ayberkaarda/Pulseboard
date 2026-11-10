import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { HealthState } from './signal.model';

/**
 * Health is encoded twice: by color and by shape (circle, triangle, square,
 * dashed ring, ring with clock hands), so the state survives color-vision deficiency, grayscale
 * screenshots and forced-colors mode. The glyph is decorative; the caller
 * always renders the state's text label next to it.
 */
@Component({
  selector: 'app-health-glyph',
  template: `
    <svg viewBox="0 0 12 12" aria-hidden="true" focusable="false">
      @switch (state()) {
        @case ('healthy') {
          <circle cx="6" cy="6" r="4.5" />
        }
        @case ('degraded') {
          <path d="M6 1.5l5 9H1z" />
        }
        @case ('down') {
          <rect x="1.75" y="1.75" width="8.5" height="8.5" rx="1" />
        }
        @case ('stale') {
          <circle class="ring" cx="6" cy="6" r="4.5" />
          <path class="hands" d="M6 3.4V6l1.9 1.2" />
        }
        @case ('unknown') {
          <circle class="hollow" cx="6" cy="6" r="4" />
        }
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      width: var(--pb-glyph-size, 12px);
      height: var(--pb-glyph-size, 12px);
      flex: none;
      color: var(--pb-health-unknown);
    }
    :host([data-state='healthy']) {
      color: var(--pb-health-healthy);
    }
    :host([data-state='degraded']) {
      color: var(--pb-health-degraded);
    }
    :host([data-state='down']) {
      color: var(--pb-health-down);
    }
    svg {
      width: 100%;
      height: 100%;
      fill: currentColor;
    }
    .ring,
    .hands {
      fill: none;
      stroke: currentColor;
      stroke-width: 1.4;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .hollow {
      fill: none;
      stroke: currentColor;
      stroke-width: 1.5;
      stroke-dasharray: 2 1.6;
    }
  `,
  host: { '[attr.data-state]': 'state()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HealthGlyphComponent {
  readonly state = input.required<HealthState>();
}
