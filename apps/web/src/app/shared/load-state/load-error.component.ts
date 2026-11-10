import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

import { ApiProblem, describeProblem } from '../../api/problem';

/** A failed load, said plainly, with a way to try again. Announced as soon as it appears. */
@Component({
  selector: 'app-load-error',
  imports: [MatButtonModule],
  template: `
    <div class="panel" role="alert">
      <h2 class="title">{{ heading() }}</h2>
      <p class="body">{{ message() }}</p>
      <button mat-stroked-button type="button" (click)="retry.emit()">Try again</button>
    </div>
  `,
  styles: `
    .panel {
      display: grid;
      justify-items: start;
      gap: var(--pb-space-3);
      padding: var(--pb-space-5) var(--pb-space-6);
      background: var(--pb-surface);
      border: 1px solid var(--pb-line-strong);
      border-radius: var(--pb-radius-md);
    }
    .title {
      font-size: var(--pb-text-xl);
      font-stretch: var(--pb-stretch-semi);
    }
    .body {
      color: var(--pb-ink-2);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoadErrorComponent {
  readonly problem = input.required<ApiProblem>();
  readonly heading = input('Could not load this');
  // eslint-disable-next-line @typescript-eslint/no-invalid-void-type
  readonly retry = output<void>();

  protected readonly message = computed(() => describeProblem(this.problem()));
}
