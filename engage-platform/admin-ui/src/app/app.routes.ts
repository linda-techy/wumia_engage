import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/guards';

export const routes: Routes = [
  { path: 'login', canActivate: [guestGuard], loadComponent: () => import('./features/auth/login') },
  { path: 'set-password', loadComponent: () => import('./features/auth/set-password') },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./core/layout/shell'),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', loadComponent: () => import('./features/dashboard/dashboard') },
      { path: 'campaigns', loadComponent: () => import('./features/campaigns/campaigns') },
      { path: 'campaigns/new', loadComponent: () => import('./features/campaigns/campaign-composer') },
      { path: 'campaigns/:id', loadComponent: () => import('./features/campaigns/campaign-detail') },
      { path: 'campaigns/:id/edit', loadComponent: () => import('./features/campaigns/campaign-composer') },
      { path: 'segments', loadComponent: () => import('./features/segments/segments') },
      { path: 'segments/new', loadComponent: () => import('./features/segments/segment-builder') },
      { path: 'segments/:id', loadComponent: () => import('./features/segments/segment-builder') },
      { path: 'inspector', loadComponent: () => import('./features/inspector/inspector') },
      { path: 'settings', loadComponent: () => import('./features/settings/settings') },
      { path: 'templates', loadComponent: () => import('./features/templates/templates') },
      { path: 'exports', loadComponent: () => import('./features/exports/exports') },
      { path: 'operators', loadComponent: () => import('./features/operators/operators') },
      { path: 'account', loadComponent: () => import('./features/account/account') },
      { path: 'ingest', loadComponent: () => import('./features/ingest/ingest-health') },
      { path: 'customers', loadComponent: () => import('./features/customers/customer-lookup') },
      { path: 'customers/:id', loadComponent: () => import('./features/customers/customer-view') },
      { path: 'payments', loadComponent: () => import('./features/payments/payment-failures') },
      { path: 'consent-copy', loadComponent: () => import('./features/consent/consent-copy') },
    ],
  },
  { path: '**', redirectTo: '' },
];
