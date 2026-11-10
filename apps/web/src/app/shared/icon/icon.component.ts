import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

interface IconPath {
  readonly d: string;
  readonly filled?: boolean;
}

/**
 * Pulseboard icon set: drawn on a 24px grid with a 1.6px stroke, round caps
 * and joins, and no fills except where a fill carries meaning (theme state).
 * Shapes reuse the product's own vocabulary: tiles, traces and rings.
 */
const ICONS = {
  overview: [
    { d: 'M4.5 4.5h6v6h-6z' },
    { d: 'M13.5 4.5h6v3.5h-6z' },
    { d: 'M13.5 11h6v8.5h-6z' },
    { d: 'M4.5 13.5h6v6h-6z' },
  ],
  services: [
    { d: 'M4.5 5.5h15v4h-15z' },
    { d: 'M4.5 14.5h15v4h-15z' },
    { d: 'M7.5 7.5h.01M7.5 16.5h.01' },
  ],
  anomalies: [
    { d: 'M3 15.5h4.5l2-4 3 6 3.5-10' },
    { d: 'M18 5.5a2 2 0 1 0 .01 0' },
    { d: 'M3 19.5h18' },
  ],
  rules: [{ d: 'M4 7h9M17 7h3M4 17h3M11 17h9' }, { d: 'M15 4.75v4.5M9 14.75v4.5' }],
  key: [
    { d: 'M6.5 6.5h.01' },
    { d: 'M5 18.5l1.5-3 1.5 3z' },
    { d: 'M5 11h3v3H5z' },
    { d: 'M11 6.5h8.5M11 12.5h8.5M11 17.5h8.5' },
  ],
  menu: [{ d: 'M4 7h16M4 12h16M4 17h10' }],
  eye: [
    { d: 'M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z' },
    { d: 'M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z' },
  ],
  'eye-off': [
    {
      d: 'M5.2 7.2C3.4 8.6 2.5 12 2.5 12s3.5 6 9.5 6c1.6 0 3-.4 4.2-1M9.5 6.2C10.3 6.1 11.1 6 12 6c6 0 9.5 6 9.5 6s-.7 1.2-1.9 2.5',
    },
    { d: 'M9.9 9.9a2.5 2.5 0 0 0 3.5 3.5M4 4l16 16' },
  ],
  logout: [{ d: 'M10 4.5H5.5v15H10' }, { d: 'M9 12h11M16.5 8.5L20 12l-3.5 3.5' }],
  close: [{ d: 'M6 6l12 12M18 6L6 18' }],
  'theme-system': [
    { d: 'M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16z' },
    { d: 'M12 4a8 8 0 0 1 0 16z', filled: true },
  ],
  'theme-light': [
    { d: 'M12 8.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z' },
    {
      d: 'M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4',
    },
  ],
  'theme-dark': [{ d: 'M19.5 14.5A7.5 7.5 0 0 1 9.5 4.5a7.5 7.5 0 1 0 10 10z' }],
} as const satisfies Record<string, readonly IconPath[]>;

export type IconName = keyof typeof ICONS;

@Component({
  selector: 'app-icon',
  template: `
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      @for (path of paths(); track $index) {
        <path [attr.d]="path.d" [class.filled]="path.filled" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      width: var(--pb-icon-size, 20px);
      height: var(--pb-icon-size, 20px);
      flex: none;
    }
    svg {
      width: 100%;
      height: 100%;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.6;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .filled {
      fill: currentColor;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IconComponent {
  readonly name = input.required<IconName>();

  protected readonly paths = computed<readonly IconPath[]>(() => ICONS[this.name()]);
}
