import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * An empty panel drawn as a flat trace with an open ring at its end:
 * the line is waiting for its first point. Projected content holds actions.
 */
@Component({
  selector: 'app-empty-state',
  template: `
    <svg class="flatline" viewBox="0 0 480 40" preserveAspectRatio="none" aria-hidden="true">
      <path class="grid" d="M0 8h480M0 32h480" />
      <path class="trace" d="M0 20h480" />
    </svg>
    <span class="ring" aria-hidden="true"></span>
    <h2 class="title">{{ heading() }}</h2>
    <p class="body">{{ body() }}</p>
    <div class="actions"><ng-content /></div>
  `,
  styleUrl: './empty-state.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyStateComponent {
  readonly heading = input.required<string>();
  readonly body = input.required<string>();
}
