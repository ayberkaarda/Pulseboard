import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
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

@Component({
  selector: 'app-login-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
    IconComponent,
  ],
  templateUrl: './login.page.html',
  styleUrl: './auth-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    tenantSlug: ['', [Validators.required, Validators.maxLength(63)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
  });
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly passwordVisible = signal(false);

  protected readonly registerLink = ['/register'];
  protected readonly registerQuery = (() => {
    const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
    return returnUrl ? { returnUrl } : {};
  })();

  protected async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.formError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      focusFirstInvalid(this.host.nativeElement);
      return;
    }
    this.submitting.set(true);
    try {
      const { tenantSlug, email, password } = this.form.getRawValue();
      await this.session.login({
        tenantSlug: tenantSlug.trim().toLowerCase(),
        email: email.trim(),
        password,
      });
      const target =
        safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')) ?? DEFAULT_LANDING_URL;
      await this.router.navigateByUrl(target);
    } catch (error) {
      if (isApiError(error) && error.problem.status === 401) {
        this.formError.set('The workspace, email or password is not right.');
      } else if (isApiError(error)) {
        this.formError.set(applyProblem(this.form, error.problem));
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
