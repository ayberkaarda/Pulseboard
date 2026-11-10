import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** The heading block every page starts with: a title, one line of context and optional actions. */
@Component({
  selector: 'app-page-head',
  template: `
    <div class="text">
      <h1 class="page-title">{{ title() }}</h1>
      @if (summary(); as text) {
        <p class="page-summary">{{ text }}</p>
      }
    </div>
    <div class="actions"><ng-content /></div>
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: var(--pb-space-4);
      margin-bottom: var(--pb-space-6);
    }
    .page-title {
      font-size: var(--pb-text-3xl);
      line-height: 1.1;
      font-weight: 600;
      font-stretch: var(--pb-stretch-condensed);
      letter-spacing: -0.02em;
    }
    .page-summary {
      max-width: 60ch;
      margin-top: var(--pb-space-2);
      color: var(--pb-ink-2);
      font-size: var(--pb-text-lg);
    }
    @media (max-width: 959.98px) {
      .page-title {
        font-size: var(--pb-text-2xl);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeadComponent {
  readonly title = input.required<string>();
  readonly summary = input<string>();
}
