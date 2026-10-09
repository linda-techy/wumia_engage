import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HaltBar } from '../../features/halt/halt-bar';
import { Role } from '../api/models';
import { AuthService } from '../auth/auth.service';
import { TokenService } from '../auth/token.service';

interface NavItem {
  path: string;
  label: string;
  role: Role;
}

const NAV: NavItem[] = [
  { path: '/dashboard', label: 'Dashboard', role: 'VIEWER' },
  { path: '/campaigns', label: 'Campaigns', role: 'VIEWER' },
  { path: '/segments', label: 'Segments', role: 'VIEWER' },
  { path: '/inspector', label: 'Journey inspector', role: 'ANALYST' },
  { path: '/customers', label: 'Customers', role: 'VIEWER' },
  { path: '/settings', label: 'Settings', role: 'VIEWER' },
  { path: '/templates', label: 'Templates', role: 'VIEWER' },
  { path: '/consent-copy', label: 'Consent copy', role: 'VIEWER' },
  { path: '/payments', label: 'Payment failures', role: 'VIEWER' },
  { path: '/ingest', label: 'Ingest health', role: 'VIEWER' },
  { path: '/exports', label: 'Exports', role: 'ANALYST' },
  { path: '/operators', label: 'Operators', role: 'OWNER' },
  { path: '/account', label: 'Account', role: 'VIEWER' },
];

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, HaltBar],
  template: `
    <div class="flex min-h-screen bg-slate-50 text-slate-900">
      <nav class="flex w-56 shrink-0 flex-col border-r border-slate-200 bg-white" aria-label="Main">
        <div class="px-5 py-4 text-lg font-semibold tracking-tight">WUMIKA <span class="text-slate-400">Engage</span></div>
        <ul class="flex-1 space-y-1 px-3">
          @for (item of nav(); track item.path) {
            <li>
              <a
                [routerLink]="item.path"
                routerLinkActive
                ariaCurrentWhenActive="page"
                class="block rounded px-3 py-2 text-sm hover:bg-slate-100
                  aria-[current=page]:bg-slate-900 aria-[current=page]:text-white aria-[current=page]:hover:bg-slate-900"
                >{{ item.label }}</a
              >
            </li>
          }
        </ul>
        <div class="border-t border-slate-200 p-4 text-sm">
          <p class="truncate font-medium" [title]="operator()?.email ?? ''">{{ operator()?.email }}</p>
          <p class="text-xs text-slate-500">{{ roles() }}</p>
          <button type="button" class="mt-3 text-sm text-slate-700 underline" (click)="signOut()">Sign out</button>
        </div>
      </nav>
      <div class="flex min-w-0 flex-1 flex-col">
        <header class="flex min-h-14 items-center justify-end border-b border-slate-200 bg-white px-8 py-2" aria-label="Kill switches">
          <app-halt-bar />
        </header>
        <main class="min-w-0 flex-1 p-8">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export default class Shell {
  readonly #tokens = inject(TokenService);
  readonly #auth = inject(AuthService);
  readonly #router = inject(Router);

  readonly operator = this.#tokens.operator;
  readonly roles = computed(() => this.#tokens.roles().join(', '));
  readonly nav = computed(() => NAV.filter((n) => this.#tokens.has(n.role)));

  async signOut(): Promise<void> {
    await this.#auth.logout();
    await this.#router.navigate(['/login']);
  }
}
