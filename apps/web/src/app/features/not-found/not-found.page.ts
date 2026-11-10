import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { BrandMarkComponent } from '../../shared/brand/brand-mark.component';

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink, BrandMarkComponent],
  template: `
    <main class="wrap" id="main">
      <app-brand-mark />
      <h1>Page not found</h1>
      <p>There is nothing at this address.</p>
      <a routerLink="/app/overview">Go to the overview</a>
    </main>
  `,
  styles: `
    .wrap {
      display: grid;
      justify-items: center;
      gap: var(--pb-space-3);
      min-height: 100%;
      align-content: center;
      padding: var(--pb-space-7) var(--pb-space-4);
      text-align: center;
    }
    h1 {
      font-size: var(--pb-text-2xl);
      font-stretch: var(--pb-stretch-condensed);
    }
    p {
      color: var(--pb-ink-2);
    }
    a {
      color: var(--pb-ink);
      font-weight: 560;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundPage {}
