import { BreakpointObserver } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSidenavModule } from '@angular/material/sidenav';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { SessionStore } from '../core/session/session.store';
import { BrandMarkComponent } from '../shared/brand/brand-mark.component';
import { IconComponent } from '../shared/icon/icon.component';
import { NAV_ITEMS, NavItem } from './nav-items';
import { ThemeSwitchComponent } from './theme-switch/theme-switch.component';

/** Rail is docked from this width up; below it the navigation is a drawer. */
export const WIDE_LAYOUT_QUERY = '(min-width: 960px)';

@Component({
  selector: 'app-shell',
  imports: [
    MatButtonModule,
    MatSidenavModule,
    RouterLink,
    RouterOutlet,
    BrandMarkComponent,
    IconComponent,
    ThemeSwitchComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly session = inject(SessionStore);
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  protected readonly navItems = NAV_ITEMS;
  protected readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe(WIDE_LAYOUT_QUERY)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  protected readonly drawerOpen = signal(false);
  private navigations = 0;

  constructor() {
    // After a route change focus moves to the page, so keyboard and screen reader users start at
    // the new content. The first render keeps the browser default.
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        this.navigations += 1;
        this.drawerOpen.set(false);
        if (this.navigations > 1) {
          this.main().nativeElement.focus();
        }
      });
  }

  protected isActive(item: NavItem): boolean {
    const path = this.url().split(/[?#]/)[0];
    return item.matches.some((prefix) => path === prefix || path.startsWith(`${prefix}/`));
  }

  protected async signOut(): Promise<void> {
    this.session.logout();
    await this.router.navigateByUrl('/login');
  }

  /** The dialog and its overlay machinery load on first use, outside the initial bundle. */
  protected async openSignalKey(): Promise<void> {
    const [{ MatDialog }, { SignalKeyDialogComponent }] = await Promise.all([
      import('@angular/material/dialog'),
      import('./signal-key/signal-key-dialog.component'),
    ]);
    this.injector.get(MatDialog).open(SignalKeyDialogComponent, {
      width: '640px',
      maxWidth: 'calc(100vw - 32px)',
      autoFocus: 'dialog',
    });
  }

  protected skipToContent(): void {
    this.main().nativeElement.focus();
  }
}
