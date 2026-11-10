import { Routes } from '@angular/router';

import { unsavedChangesGuard } from './unsaved-changes.guard';

/**
 * Alerts. The editor routes come first and sit outside the tabbed frame: an editor is a task of its
 * own with a form to leave carefully.
 */
export const ALERT_ROUTES: Routes = [
  {
    path: 'rules/new',
    title: 'New alert rule - Pulseboard',
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./alert-rule-editor.page').then((m) => m.AlertRuleEditorPage),
  },
  {
    path: 'rules/:id',
    title: 'Edit alert rule - Pulseboard',
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./alert-rule-editor.page').then((m) => m.AlertRuleEditorPage),
  },
  {
    path: '',
    loadComponent: () => import('./alerts.page').then((m) => m.AlertsPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'rules' },
      {
        path: 'rules',
        title: 'Alert rules - Pulseboard',
        loadComponent: () => import('./alert-rules.page').then((m) => m.AlertRulesPage),
      },
      {
        path: 'history',
        title: 'Alert history - Pulseboard',
        loadComponent: () => import('./alert-history.page').then((m) => m.AlertHistoryPage),
      },
    ],
  },
];
