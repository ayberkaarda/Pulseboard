import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonToggleChange, MatButtonToggleModule } from '@angular/material/button-toggle';

import { ThemePreference, ThemeService } from '../../core/theme/theme.service';
import { IconComponent, IconName } from '../../shared/icon/icon.component';

interface ThemeOption {
  readonly value: ThemePreference;
  readonly label: string;
  readonly icon: IconName;
}

@Component({
  selector: 'app-theme-switch',
  imports: [MatButtonToggleModule, IconComponent],
  template: `
    <mat-button-toggle-group
      class="switch"
      aria-label="Color theme"
      hideSingleSelectionIndicator
      [value]="theme.preference()"
      (change)="select($event)"
    >
      @for (option of options; track option.value) {
        <mat-button-toggle [value]="option.value" [aria-label]="option.label">
          <app-icon [name]="option.icon" />
        </mat-button-toggle>
      }
    </mat-button-toggle-group>
  `,
  styles: `
    .switch {
      --pb-icon-size: 18px;
      --mat-button-toggle-height: 36px;
      width: 100%;
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      border-color: var(--pb-line);
    }
    app-icon {
      vertical-align: middle;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ThemeSwitchComponent {
  protected readonly theme = inject(ThemeService);

  protected readonly options: readonly ThemeOption[] = [
    { value: 'system', label: 'Match system theme', icon: 'theme-system' },
    { value: 'light', label: 'Light theme', icon: 'theme-light' },
    { value: 'dark', label: 'Dark theme', icon: 'theme-dark' },
  ];

  protected select(change: MatButtonToggleChange): void {
    const value: unknown = change.value;
    if (value === 'system' || value === 'light' || value === 'dark') {
      this.theme.setPreference(value);
    }
  }
}
