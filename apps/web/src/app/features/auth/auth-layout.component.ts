import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { BrandMarkComponent } from '../../shared/brand/brand-mark.component';
import { ThemeSwitchComponent } from '../../shell/theme-switch/theme-switch.component';

/** Frame for the sign-in pages: brand above one centred card, theme switch below. */
@Component({
  selector: 'app-auth-layout',
  imports: [RouterOutlet, BrandMarkComponent, ThemeSwitchComponent],
  template: `
    <div class="auth">
      <div class="brand">
        <app-brand-mark [animate]="true" />
        <span class="pb-wordmark">pulseboard</span>
      </div>
      <main id="main" class="card">
        <router-outlet />
      </main>
      <app-theme-switch />
    </div>
  `,
  styles: `
    :host {
      display: block;
      min-height: 100%;
    }
    .auth {
      display: grid;
      justify-items: center;
      align-content: center;
      gap: var(--pb-space-5);
      min-height: 100vh;
      padding: var(--pb-space-6) var(--pb-space-4);
    }
    .brand {
      display: flex;
      align-items: center;
      gap: var(--pb-space-3);
    }
    .card {
      width: min(100%, 26rem);
      padding: var(--pb-space-6);
      background: var(--pb-surface);
      border: 1px solid var(--pb-line);
      border-radius: var(--pb-radius-lg);
    }
    @media (max-width: 599.98px) {
      .card {
        padding: var(--pb-space-5) var(--pb-space-4);
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthLayoutComponent {}
