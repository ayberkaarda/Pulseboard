import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The Pulseboard mark is the product's own chart in miniature: a trace
 * running inside its expected range, one peak that breaks out of the range,
 * and a flare-colored anomaly marker on that peak. The trace draws itself
 * once on first render; reduced-motion users get the finished mark.
 */
@Component({
  selector: 'app-brand-mark',
  template: `
    <svg viewBox="0 0 32 32" aria-hidden="true" focusable="false" [class.animate]="animate()">
      <rect class="tile" width="32" height="32" rx="8" />
      <rect class="band" x="4" y="15" width="24" height="8" rx="1.5" />
      <path
        class="trace"
        pathLength="1"
        d="M5 19.5l3.5-1.5 3.5 2 3-1.5 3.5-6.5 3.5 7.5 3-1.5 3 .5"
      />
      <circle class="flare" cx="18.5" cy="9.4" r="3" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      width: var(--pb-mark-size, 32px);
      height: var(--pb-mark-size, 32px);
      flex: none;
    }
    svg {
      width: 100%;
      height: 100%;
    }
    .tile {
      fill: var(--pb-ink);
    }
    .band {
      fill: var(--pb-ink-inverse);
      opacity: 0.16;
    }
    .trace {
      fill: none;
      stroke: var(--pb-ink-inverse);
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .flare {
      fill: var(--pb-flare);
      stroke: var(--pb-ink);
      stroke-width: 1.5;
    }
    .animate .trace {
      stroke-dasharray: 1;
      animation: draw var(--pb-duration-trace) var(--pb-ease-standard) both;
    }
    .animate .flare {
      transform-origin: 18.5px 9.4px;
      animation: flare var(--pb-duration-slow) var(--pb-ease-standard) var(--pb-duration-trace) both;
    }
    @keyframes draw {
      from {
        stroke-dashoffset: 1;
      }
      to {
        stroke-dashoffset: 0;
      }
    }
    @keyframes flare {
      from {
        transform: scale(0);
      }
      to {
        transform: scale(1);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandMarkComponent {
  readonly animate = input(false);
}
