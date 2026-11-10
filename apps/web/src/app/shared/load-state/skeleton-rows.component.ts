import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Placeholder rows while a list loads. The motion stops under reduced motion (see _base.scss). */
@Component({
  selector: 'app-skeleton-rows',
  template: `
    <div role="status">
      <span class="pb-visually-hidden">{{ label() }}</span>
      @for (row of rows; track row) {
        <div class="row" aria-hidden="true"></div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .row {
      height: 64px;
      margin-bottom: var(--pb-space-2);
      background: var(--pb-surface-sunken);
      border: 1px solid var(--pb-line);
      border-radius: var(--pb-radius-md);
      animation: pulse 1.4s ease-in-out infinite;
    }
    @keyframes pulse {
      50% {
        opacity: 0.55;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .row {
        animation: none;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkeletonRowsComponent {
  readonly label = input('Loading');
  protected readonly rows = [0, 1, 2];
}
