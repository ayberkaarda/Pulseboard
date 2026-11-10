import { ScatterChart, LineChart } from 'echarts/charts';
import { GridComponent, MarkAreaComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';

// Only what the metric chart draws: two series types, a grid, a tooltip, shaded windows and the
// canvas renderer. Loaded on demand (see MetricChartComponent), so none of it is in the initial bundle.
echarts.use([
  LineChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  MarkAreaComponent,
  CanvasRenderer,
]);

export { echarts };
