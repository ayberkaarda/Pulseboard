import { BreakpointObserver } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSidenavModule } from '@angular/material/sidenav';
import { RouterOutlet } from '@angular/router';
import { map } from 'rxjs';

import { ThemeService } from './core/theme/theme.service';
import { BrandMarkComponent } from './shared/brand/brand-mark.component';
import { IconComponent } from './shared/icon/icon.component';
import { EmptyStateComponent } from './shell/empty-state/empty-state.component';
import { SHELL_SECTIONS, SectionId, ShellSection } from './shell/sections';
import { ThemeSwitchComponent } from './shell/theme-switch/theme-switch.component';

/** Rail is docked from this width up; below it the navigation is a drawer. */
export const WIDE_LAYOUT_QUERY = '(min-width: 960px)';

@Component({
  selector: 'app-root',
  imports: [
    MatButtonModule,
    MatSidenavModule,
    RouterOutlet,
    BrandMarkComponent,
    EmptyStateComponent,
    IconComponent,
    ThemeSwitchComponent,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly injector = inject(Injector);
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  protected readonly sections = SHELL_SECTIONS;
  protected readonly activeId = signal<SectionId>('overview');
  protected readonly active = computed<ShellSection>(
    () => this.sections.find((section) => section.id === this.activeId()) ?? this.sections[0],
  );

  protected readonly wide = toSignal(
    inject(BreakpointObserver)
      .observe(WIDE_LAYOUT_QUERY)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  protected readonly drawerOpen = signal(false);

  constructor() {
    // Instantiated here so the stored theme applies on startup.
    inject(ThemeService);
  }

  protected selectSection(id: SectionId): void {
    this.activeId.set(id);
    this.drawerOpen.set(false);
  }

  /** The dialog and its overlay machinery load on first use, outside the initial bundle. */
  protected async openSignalKey(): Promise<void> {
    const [{ MatDialog }, { SignalKeyDialogComponent }] = await Promise.all([
      import('@angular/material/dialog'),
      import('./shell/signal-key/signal-key-dialog.component'),
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
