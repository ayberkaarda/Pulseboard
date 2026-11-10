import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';

import { ConfirmService } from '../../shared/confirm/confirm.service';
import { HasUnsavedChanges, unsavedChangesGuard } from './unsaved-changes.guard';

function run(component: HasUnsavedChanges) {
  return TestBed.runInInjectionContext(() =>
    unsavedChangesGuard(
      component,
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
      {} as RouterStateSnapshot,
    ),
  );
}

describe('unsavedChangesGuard', () => {
  let confirm: jest.Mock;

  beforeEach(() => {
    confirm = jest.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: ConfirmService, useValue: { confirm } }],
    });
  });

  it('lets the person leave a form without edits, without asking', () => {
    expect(run({ hasUnsavedChanges: () => false })).toBe(true);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('asks when there are edits, and stays when the person keeps editing', async () => {
    confirm.mockResolvedValue(false);
    await expect(run({ hasUnsavedChanges: () => true })).resolves.toBe(false);
    expect(confirm).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Discard your changes?',
        confirmLabel: 'Discard changes',
        cancelLabel: 'Keep editing',
      }),
    );
  });

  it('leaves when the person agrees to discard', async () => {
    confirm.mockResolvedValue(true);
    await expect(run({ hasUnsavedChanges: () => true })).resolves.toBe(true);
  });
});
