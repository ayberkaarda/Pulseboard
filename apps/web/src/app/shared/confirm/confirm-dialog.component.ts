import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

export interface ConfirmOptions {
  readonly title: string;
  /** Plain text; it is rendered as text, never as markup. */
  readonly body: string;
  readonly confirmLabel: string;
  readonly cancelLabel?: string;
}

/**
 * A question with two answers. Focus starts on the safe answer (Cancel), Escape cancels, and the
 * dialog machinery returns focus to the control that opened it.
 */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title class="title">{{ data.title }}</h2>
    <mat-dialog-content>
      <p class="body">{{ data.body }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button type="button" [mat-dialog-close]="false" data-autofocus>
        {{ data.cancelLabel ?? 'Cancel' }}
      </button>
      <button mat-flat-button type="button" [mat-dialog-close]="true">
        {{ data.confirmLabel }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .title {
      font-size: var(--pb-text-2xl);
      font-weight: 600;
      font-stretch: var(--pb-stretch-condensed);
      letter-spacing: -0.015em;
    }
    .body {
      max-width: 52ch;
      color: var(--pb-ink-2);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialogComponent {
  protected readonly data = inject<ConfirmOptions>(MAT_DIALOG_DATA);
}
