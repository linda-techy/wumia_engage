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
      { path: '', pathMatch: 'full', redirectTo: 'ingest' },
      { path: 'ingest', loadComponent: () => import('./features/ingest/ingest-health') },
      { path: 'customers', loadComponent: () => import('./features/customers/customer-lookup') },
      { path: 'customers/:id', loadComponent: () => import('./features/customers/customer-view') },
      { path: 'payments', loadComponent: () => import('./features/payments/payment-failures') },
      { path: 'consent-copy', loadComponent: () => import('./features/consent/consent-copy') },
    ],
  },
  { path: '**', redirectTo: '' },
];
