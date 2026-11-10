import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { isApiError } from '../../api/problem';
import { DEFAULT_LANDING_URL, safeReturnUrl } from '../../core/navigation/return-url';
import { SessionStore } from '../../core/session/session.store';
import { IconComponent } from '../../shared/icon/icon.component';
import { applyProblem, focusFirstInvalid } from './form-errors';

/** Same rule as the contract: 3 to 63 characters, lower case letters, digits and inner hyphens. */
export const TENANT_SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/;

export function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+/, '')
    .slice(0, 63)
    .replace(/-+$/, '');
}

@Component({
  selector: 'app-register-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
    IconComponent,
  ],
  templateUrl: './register.page.html',
  styleUrl: './auth-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterPage {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    tenantName: ['', [Validators.required, Validators.maxLength(200)]],
    tenantSlug: ['', [Validators.required, Validators.pattern(TENANT_SLUG_PATTERN)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(128)]],
  });
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly passwordVisible = signal(false);

  protected readonly loginQuery = (() => {
    const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
    return returnUrl ? { returnUrl } : {};
  })();

  constructor() {
    // The workspace address follows the name until the person writes their own.
    this.form.controls.tenantName.valueChanges.pipe(takeUntilDestroyed()).subscribe((name) => {
      const slug = this.form.controls.tenantSlug;
      if (!slug.dirty) {
        slug.setValue(slugify(name));
      }
    });
  }

  protected async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      focusFirstInvalid(this.host.nativeElement, this.form);
      return;
    }
    this.submitting.set(true);
    try {
      const { tenantName, tenantSlug, email, password } = this.form.getRawValue();
      await this.session.register({
        tenantName: tenantName.trim(),
        tenantSlug,
        email: email.trim(),
        password,
      });
      const target =
        safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')) ?? DEFAULT_LANDING_URL;
      await this.router.navigateByUrl(target);
    } catch (error) {
      if (isApiError(error) && error.problem.status === 409) {
        this.form.controls.tenantSlug.setErrors({ server: 'This workspace address is taken.' });
        this.form.controls.tenantSlug.markAsTouched();
        focusFirstInvalid(this.host.nativeElement, this.form);
      } else if (isApiError(error)) {
        this.formError.set(applyProblem(this.form, error.problem));
        focusFirstInvalid(this.host.nativeElement, this.form);
      } else {
        this.formError.set('Something went wrong. Try again.');
      }
    } finally {
      this.submitting.set(false);
    }
  }

  protected togglePassword(): void {
    this.passwordVisible.update((visible) => !visible);
  }
}
