import { AbstractControl, FormGroup } from '@angular/forms';

import { ApiProblem, describeProblem } from '../../api/problem';

/**
 * Puts the server's field errors on the matching controls and returns the text for the form as a whole.
 * Messages for fields come from the server's validation text (short, about the field) and stay until the value is edited, which re-runs validation; anything
 * that is not tied to a field is described from the status only.
 */
export function applyProblem(form: FormGroup, problem: ApiProblem): string | null {
  let placed = 0;
  for (const { field, message } of problem.fieldErrors) {
    const control: AbstractControl | null = form.get(field);
    if (control) {
      control.setErrors({ server: message });
      control.markAsTouched();
      placed += 1;
    }
  }
  if (placed > 0) {
    return null;
  }
  return describeProblem(problem);
}

/** Moves focus to the first field, in form order, that has an error. */
export function focusFirstInvalid(root: HTMLElement, form: FormGroup): void {
  for (const [name, control] of Object.entries(form.controls)) {
    if (control.invalid) {
      root.querySelector<HTMLElement>(`[formcontrolname="${name}"]`)?.focus();
      return;
    }
  }
}
