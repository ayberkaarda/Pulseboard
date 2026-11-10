import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type { EChartsCoreOption } from 'echarts/core';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';

import { Aggregation, Anomaly, MetricPoint } from '../../api/models';
import { buildChartOption, chartLabel } from './chart-options';
import { ChartTokens, readChartTokens } from './chart-tokens';

/**
 * The metric time series. ECharts is imported here, on demand, and nowhere else, so the library is
 * a separate chunk that only the metric page ever loads.
 *
 * The container is an image for assistive technology (role img with a label that states min, max,
 * last and the number of anomalies); the same numbers are available as text and as a table on the
 * page, because the canvas itself is not keyboard navigable.
 */
@Component({
  selector: 'app-metric-chart',
  imports: [NgxEchartsDirective],
  providers: [
    provideEchartsCore({
      echarts: () => import('./echarts-setup').then((module) => module.echarts),
    }),
  ],
  template: `
    <div #frame class="frame">
      @if (option(); as chartOption) {
        <div
          class="canvas"
          role="img"
          echarts
          [options]="chartOption"
          [autoResize]="true"
          [attr.aria-label]="label()"
        ></div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .frame,
    .canvas {
      width: 100%;
      height: 100%;
      min-height: 280px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricChartComponent {
  readonly points = input.required<readonly MetricPoint[]>();
  readonly anomalies = input.required<readonly Anomaly[]>();
  readonly fromMs = input.required<number>();
  readonly toMs = input.required<number>();
  readonly stepSeconds = input.required<number>();
  readonly name = input.required<string>();
  readonly unit = input('');
  readonly aggregation = input<Aggregation | null>(null);

  private readonly document = inject(DOCUMENT);
  private readonly frame = viewChild.required<ElementRef<HTMLElement>>('frame');
  private readonly tokens = signal<ChartTokens | null>(null);
  private readonly reducedMotion = signal(false);

  protected readonly label = computed(() =>
    chartLabel({
      name: this.name(),
      unit: this.unit(),
      aggregation: this.aggregation(),
      points: this.points(),
      anomalyCount: this.anomalies().length,
      stepSeconds: this.stepSeconds(),
      fromMs: this.fromMs(),
      toMs: this.toMs(),
    }),
  );

  /** Rebuilt whenever the data, the tokens (theme) or the motion preference change. */
  protected readonly option = computed<EChartsCoreOption | null>(() => {
    const tokens = this.tokens();
    if (tokens === null) {
      return null;
    }
    return buildChartOption({
      points: this.points(),
      anomalies: this.anomalies(),
      fromMs: this.fromMs(),
      toMs: this.toMs(),
      stepSeconds: this.stepSeconds(),
      unit: this.unit(),
      aggregation: this.aggregation(),
      tokens,
      reducedMotion: this.reducedMotion(),
    });
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    const view = this.document.defaultView;
    afterNextRender(() => this.refreshTokens());
    if (view === null) {
      return;
    }
    // The theme switch changes data-theme on <html>; "system" follows the OS colour scheme.
    const observer = new MutationObserver(() => this.refreshTokens());
    observer.observe(this.document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
    const media = [
      view.matchMedia('(prefers-color-scheme: dark)'),
      view.matchMedia('(prefers-reduced-motion: reduce)'),
    ];
    const onMedia = (): void => this.refreshTokens();
    for (const query of media) {
      query.addEventListener('change', onMedia);
    }
    destroyRef.onDestroy(() => {
      observer.disconnect();
      for (const query of media) {
        query.removeEventListener('change', onMedia);
      }
    });
  }

  private refreshTokens(): void {
    const host = this.frame().nativeElement;
    this.reducedMotion.set(
      this.document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches ?? false,
    );
    this.tokens.set(readChartTokens(host));
  }
}
