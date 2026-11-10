import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS, MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { startWith } from 'rxjs';

import { ApiProblem } from '../../api/problem';
import { Notifier } from '../../core/notify/notifier.service';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { SeverityGlyphComponent } from '../../shared/signal/severity-glyph.component';
import { SEVERITY_LABELS } from '../../shared/signal/signal.model';
import { ServicesStore } from '../services/services.store';
import {
  AlertSeverity,
  COOLDOWN_MAX_SECONDS,
  COOLDOWN_MIN_SECONDS,
  COOLDOWN_PRESETS,
  EMAIL_LABEL_MAX_LENGTH,
  NAME_MAX_LENGTH,
  OPERATORS,
  OPERATOR_LABELS,
  OPERATOR_SYMBOLS,
  SEVERITIES,
  describeRuleProblem,
  formatCooldown,
} from './alert-models';
import {
  RuleFormControls,
  applyConditionFields,
  applyRuleProblem,
  buildRuleRequest,
  buildUpdateRequest,
  createRuleForm,
  firstInvalidControl,
  ruleToFormValue,
  wireRuleForm,
} from './alert-rule-form';
import { AlertRulesStore } from './alert-rules.store';
import { HasUnsavedChanges } from './unsaved-changes.guard';

type SaveProblem = 'conflict' | 'gone' | null;

/**
 * Create (`rules/new`) or edit (`rules/:id`) an alert rule.
 *
 * The form keeps its values in memory only: nothing, the webhook address included, is written to
 * storage or to the console. Fields of the condition that is not chosen are removed (not hidden), the
 * target field changes meaning with the channel, and changes to those parts are announced politely
 * while focus stays where the person put it.
 */
@Component({
  selector: 'app-alert-rule-editor-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    RouterLink,
    LoadErrorComponent,
    PageHeadComponent,
    SeverityGlyphComponent,
    SkeletonRowsComponent,
  ],
  providers: [
    // Hints and errors may wrap; the next field moves down instead of being overlapped.
    { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { subscriptSizing: 'dynamic' } },
  ],
  templateUrl: './alert-rule-editor.page.html',
  styleUrl: './alert-rule-editor.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertRuleEditorPage implements HasUnsavedChanges {
  protected readonly store = inject(AlertRulesStore);
  protected readonly catalog = inject(ServicesStore);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly ruleId = inject(ActivatedRoute).snapshot.paramMap.get('id');
  protected readonly editing = this.ruleId !== null;

  protected readonly form = createRuleForm();
  private readonly controls = this.form.controls;

  protected readonly condition = toSignal(
    this.controls.condition.valueChanges.pipe(startWith(this.controls.condition.value)),
    { requireSync: true },
  );
  protected readonly channel = toSignal(
    this.controls.channel.valueChanges.pipe(startWith(this.controls.channel.value)),
    { requireSync: true },
  );
  protected readonly serviceId = toSignal(
    this.controls.serviceId.valueChanges.pipe(startWith(this.controls.serviceId.value)),
    { requireSync: true },
  );
  protected readonly metricId = toSignal(
    this.controls.metricId.valueChanges.pipe(startWith(this.controls.metricId.value)),
    { requireSync: true },
  );
  protected readonly cooldown = toSignal(
    this.controls.cooldownSeconds.valueChanges.pipe(startWith(this.controls.cooldownSeconds.value)),
    { requireSync: true },
  );

  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly saveProblem = signal<SaveProblem>(null);
  /** Said politely when a part of the form appears or changes meaning. */
  protected readonly announcement = signal('');
  private readonly saved = signal(false);
  private readonly populated = signal(false);
  private populating = false;

  protected readonly rule = computed(
    () => this.store.rules().find((entry) => entry.id === this.ruleId) ?? null,
  );

  /** What the editor shows in the place of the form, if anything. */
  protected readonly state = computed<'denied' | 'loading' | 'error' | 'missing' | 'form'>(() => {
    if (!this.store.canManage()) {
      return 'denied';
    }
    if (!this.editing) {
      return 'form';
    }
    if (this.populated()) {
      return 'form';
    }
    if (this.store.status() === 'error') {
      return 'error';
    }
    if (this.store.status() !== 'loaded') {
      return 'loading';
    }
    return this.rule() === null ? 'missing' : 'form';
  });

  protected readonly metrics = computed(() => {
    const id = this.serviceId();
    return id === '' ? [] : (this.catalog.metrics()[id]?.items ?? []);
  });

  protected readonly metricsLoading = computed(() => {
    const id = this.serviceId();
    return id !== '' && (this.catalog.metrics()[id]?.status ?? 'idle') !== 'loaded';
  });

  /** The unit of the chosen metric; for the metric the rule already watches it is on the rule. */
  protected readonly unit = computed(() => {
    const chosen = this.metrics().find((metric) => metric.id === this.metricId());
    if (chosen) {
      return chosen.unit;
    }
    const rule = this.rule();
    return rule !== null && rule.metricId === this.metricId() ? rule.metricUnit : '';
  });

  protected readonly operators = OPERATORS;
  protected readonly operatorSymbols = OPERATOR_SYMBOLS;
  protected readonly operatorLabels = OPERATOR_LABELS;
  protected readonly severities = SEVERITIES;
  protected readonly severityLabels = SEVERITY_LABELS;
  protected readonly presets = COOLDOWN_PRESETS.map((seconds) => ({
    seconds,
    label: formatCooldown(seconds),
  }));
  protected readonly limits = {
    name: NAME_MAX_LENGTH,
    cooldownMin: COOLDOWN_MIN_SECONDS,
    cooldownMax: COOLDOWN_MAX_SECONDS,
  };

  protected readonly cooldownText = computed(() => {
    const seconds = this.cooldown();
    return typeof seconds === 'number' && Number.isInteger(seconds) && seconds > 0
      ? formatCooldown(seconds)
      : '';
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    const wiring = wireRuleForm(this.form, () => this.populating);
    destroyRef.onDestroy(() => wiring.unsubscribe());

    this.controls.condition.valueChanges.pipe(takeUntilDestroyed()).subscribe((value) => {
      if (this.populating) {
        return;
      }
      this.announcement.set(
        value === 'THRESHOLD'
          ? 'Threshold fields shown: operator and value.'
          : 'Severity field shown: minimum severity. Threshold fields removed.',
      );
    });
    this.controls.channel.valueChanges.pipe(takeUntilDestroyed()).subscribe((value) => {
      if (this.populating) {
        return;
      }
      this.announcement.set(
        value === 'WEBHOOK'
          ? 'Target is now a webhook address. The previous target was cleared.'
          : 'Target is now an e-mail recipient label. The previous target was cleared.',
      );
    });

    if (this.catalog.services().status === 'idle') {
      this.catalog.loadServices();
    }
    if (this.editing) {
      // The newest version of the rule is read when the editor opens, so a save starts from it.
      this.store.load();
    }

    // The metrics of the service in the picker.
    effect(() => {
      const id = this.serviceId();
      if (id !== '') {
        this.catalog.ensureMetrics([id]);
      }
    });

    // Editing: once the rule is there, fill the form; then find the service that owns its metric
    // (the rule only carries the service name).
    effect(() => {
      const rule = this.rule();
      if (this.editing && !this.populated() && this.store.status() === 'loaded' && rule !== null) {
        untracked(() => this.populate());
      }
    });
    effect(() => {
      const services = this.catalog.services();
      if (!this.editing || services.status !== 'loaded') {
        return;
      }
      untracked(() => this.catalog.ensureMetrics(services.items.map((service) => service.id)));
    });
    effect(() => {
      const rule = this.rule();
      const catalogMetrics = this.catalog.metrics();
      if (!this.editing || rule === null || untracked(() => this.serviceId()) !== '') {
        return;
      }
      for (const [serviceId, list] of Object.entries(catalogMetrics)) {
        if (list.items.some((metric) => metric.id === rule.metricId)) {
          untracked(() => this.controls.serviceId.setValue(serviceId));
          return;
        }
      }
    });
  }

  hasUnsavedChanges(): boolean {
    return this.form.dirty && !this.saved();
  }

  private populate(): void {
    const rule = this.rule();
    if (rule === null) {
      return;
    }
    this.populating = true;
    try {
      this.form.reset(ruleToFormValue(rule));
      applyConditionFields(this.form);
    } finally {
      this.populating = false;
    }
    this.populated.set(true);
    this.form.markAsPristine();
    this.form.markAsUntouched();
    this.saveProblem.set(null);
    this.formError.set(null);
    this.announcement.set('');
  }

  protected onServiceChange(): void {
    this.controls.metricId.setValue('');
  }

  protected setCooldown(seconds: number): void {
    this.controls.cooldownSeconds.setValue(seconds);
    this.controls.cooldownSeconds.markAsDirty();
  }

  protected severityChecked(severity: AlertSeverity): boolean {
    return this.controls.minSeverity.value === severity;
  }

  /** Text under a control, or null when it has no error. Server messages win over local ones. */
  protected message(name: keyof RuleFormControls): string | null {
    const control = this.controls[name];
    const errors = control.errors;
    if (errors === null) {
      return null;
    }
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    if (typeof errors['webhook'] === 'string') {
      return errors['webhook'];
    }
    if (errors['required']) {
      return REQUIRED_MESSAGES[name] ?? 'This field is required.';
    }
    if (errors['maxlength']) {
      return name === 'target'
        ? `Use at most ${EMAIL_LABEL_MAX_LENGTH} characters.`
        : `Use at most ${NAME_MAX_LENGTH} characters.`;
    }
    if (errors['min'] || errors['max']) {
      return `Enter a number from ${COOLDOWN_MIN_SECONDS} to ${COOLDOWN_MAX_SECONDS} seconds (1 minute to 24 hours).`;
    }
    if (errors['whole']) {
      return 'Enter a whole number of seconds.';
    }
    if (errors['finite']) {
      return 'Enter a finite number.';
    }
    return 'This value is not valid.';
  }

  protected async submit(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    this.formError.set(null);
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.formError.set('Some fields need attention. The first one has focus.');
      this.focusFirstInvalid();
      return;
    }
    const raw = this.form.getRawValue();
    const rule = this.rule();
    this.submitting.set(true);
    try {
      const result =
        this.editing && rule !== null
          ? await this.store.update(rule.id, buildUpdateRequest(raw, rule.version))
          : await this.store.create(buildRuleRequest(raw));
      if (result.ok) {
        this.saved.set(true);
        void this.notifier.info('Rule saved');
        await this.router.navigate(['/app/alerts/rules']);
        return;
      }
      this.fail(result.problem);
    } finally {
      this.submitting.set(false);
    }
  }

  private fail(problem: ApiProblem): void {
    if (problem.kind === 'version-conflict') {
      this.saveProblem.set('conflict');
      return;
    }
    if (problem.status === 404 && this.editing) {
      this.saveProblem.set('gone');
      return;
    }
    if (problem.kind === 'alert-rule-limit') {
      this.formError.set(describeRuleProblem(problem, 'save'));
      return;
    }
    const general = applyRuleProblem(this.form, problem);
    this.formError.set(general ?? 'The server did not accept some fields. See the messages below.');
    if (general === null) {
      this.focusFirstInvalid();
    }
  }

  /** Throws away the edits and reads the rule as it is on the server now. */
  protected reloadLatest(): void {
    this.populated.set(false);
    this.form.markAsPristine();
    this.saveProblem.set(null);
    this.store.load();
  }

  protected dismissProblem(): void {
    this.saveProblem.set(null);
  }

  private focusFirstInvalid(): void {
    const name = firstInvalidControl(this.form);
    if (name !== null) {
      this.host.nativeElement.querySelector<HTMLElement>(`[formcontrolname="${name}"]`)?.focus();
    }
  }
}

const REQUIRED_MESSAGES: Partial<Record<keyof RuleFormControls, string>> = {
  name: 'Give the rule a name, for example "Checkout latency too high".',
  serviceId: 'Choose the service that owns the metric.',
  metricId: 'Choose the metric to watch.',
  threshold: 'Enter the value that triggers the alert.',
  cooldownSeconds: 'Enter how long the rule stays silent after it fires.',
  target: 'Enter where the notification should go.',
};
