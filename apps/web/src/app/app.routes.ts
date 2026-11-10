import { Routes } from '@angular/router';

import { authGuard, guestGuard } from './core/guards/auth.guards';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'app/overview' },
  {
    path: '',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/auth-layout.component').then((m) => m.AuthLayoutComponent),
    children: [
      {
        path: 'login',
        title: 'Sign in - Pulseboard',
        loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
      },
      {
        path: 'register',
        title: 'Create a workspace - Pulseboard',
        loadComponent: () => import('./features/auth/register.page').then((m) => m.RegisterPage),
      },
    ],
  },
  {
    path: 'app',
    canActivate: [authGuard],
    loadComponent: () => import('./shell/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'overview' },
      {
        path: 'overview',
        title: 'Overview - Pulseboard',
        loadComponent: () =>
          import('./features/overview/overview.page').then((m) => m.OverviewPage),
      },
      {
        path: 'services/:id',
        title: 'Service - Pulseboard',
        loadComponent: () =>
          import('./features/service-detail/service-detail.page').then((m) => m.ServiceDetailPage),
      },
      {
        path: 'metrics/:id',
        title: 'Metric - Pulseboard',
        loadComponent: () =>
          import('./features/metric-detail/metric-detail.page').then((m) => m.MetricDetailPage),
      },
      {
        path: 'anomalies',
        title: 'Anomalies - Pulseboard',
        loadComponent: () =>
          import('./features/anomalies/anomalies.page').then((m) => m.AnomaliesPage),
      },
      {
        path: 'alerts',
        loadChildren: () => import('./features/alerts/alerts.routes').then((m) => m.ALERT_ROUTES),
      },
      {
        path: '**',
        title: 'Not found - Pulseboard',
        loadComponent: () =>
          import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
      },
    ],
  },
  {
    path: '**',
    title: 'Not found - Pulseboard',
    loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
