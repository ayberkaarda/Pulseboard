import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type DeliveryState = 'PENDING' | 'SENT' | 'FAILED';

/**
 * Delivery state of an alert event, encoded by shape and by color: dots in a pill (waiting), a check
 * in a ring (delivered), a cross in a ring (gave up). Decorative: the caller always renders the word.
 */
@Component({
  selector: 'app-delivery-glyph',
  template: `
    <svg viewBox="0 0 12 12" aria-hidden="true" focusable="false">
      @switch (state()) {
        @case ('PENDING') {
          <rect class="line" x="0.9" y="3.4" width="10.2" height="5.2" rx="2.6" />
          <path class="dots" d="M3.4 6h.01M6 6h.01M8.6 6h.01" />
        }
        @case ('SENT') {
          <circle class="line" cx="6" cy="6" r="5" />
          <path class="line" d="M3.6 6.2l1.7 1.7 3.2-3.5" />
        }
        @case ('FAILED') {
          <circle class="line" cx="6" cy="6" r="5" />
          <path class="line" d="M4.1 4.1l3.8 3.8M7.9 4.1L4.1 7.9" />
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
    :host([data-state='SENT']) {
      color: var(--pb-health-healthy);
    }
    :host([data-state='FAILED']) {
      color: var(--pb-health-down);
    }
    svg {
      width: 100%;
      height: 100%;
      fill: none;
    }
    .line {
      stroke: currentColor;
      stroke-width: 1.4;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .dots {
      stroke: currentColor;
      stroke-width: 1.8;
      stroke-linecap: round;
    }
  `,
  host: { '[attr.data-state]': 'state()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryGlyphComponent {
  readonly state = input.required<DeliveryState>();
}
