import { Injectable, Injector, inject } from '@angular/core';

import type { ConfirmOptions } from './confirm-dialog.component';

export type { ConfirmOptions } from './confirm-dialog.component';

/** Asks the person to confirm. The dialog and Material's overlay load on first use. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly injector = inject(Injector);

  /** Resolves true only for the explicit confirm button; Escape, backdrop and Cancel resolve false. */
  async confirm(options: ConfirmOptions): Promise<boolean> {
    const [{ MatDialog }, { ConfirmDialogComponent }] = await Promise.all([
      import('@angular/material/dialog'),
      import('./confirm-dialog.component'),
    ]);
    const ref = this.injector
      .get(MatDialog)
      .open<InstanceType<typeof ConfirmDialogComponent>, ConfirmOptions, boolean>(
        ConfirmDialogComponent,
        {
          data: options,
          width: '440px',
          maxWidth: 'calc(100vw - 32px)',
          autoFocus: '[data-autofocus]',
          restoreFocus: true,
        },
      );
    return (
      (await new Promise<boolean | undefined>((resolve) => {
        ref.afterClosed().subscribe(resolve);
      })) === true
    );
  }
}
