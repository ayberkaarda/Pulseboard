import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';

import { ConfirmService } from '../../shared/confirm/confirm.service';

/** Implemented by pages with a form that must not be lost by accident. */
export interface HasUnsavedChanges {
  hasUnsavedChanges(): boolean;
}

/** Asks before leaving a form with edits. Leaving after a save, or with nothing changed, is silent. */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) => {
  if (!component.hasUnsavedChanges()) {
    return true;
  }
  return inject(ConfirmService).confirm({
    title: 'Discard your changes?',
    body: 'This rule has changes that are not saved. If you leave now, they are lost.',
    confirmLabel: 'Discard changes',
    cancelLabel: 'Keep editing',
  });
};
