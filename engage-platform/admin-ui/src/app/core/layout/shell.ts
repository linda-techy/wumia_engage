import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
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
  { path: '/analytics', label: 'Analytics', role: 'VIEWER' },
  { path: '/reports', label: 'Reports', role: 'VIEWER' },
  { path: '/campaigns', label: 'Campaigns', role: 'VIEWER' },
  { path: '/segments', label: 'Segments', role: 'VIEWER' },
  { path: '/inspector', label: 'Journey inspector', role: 'ANALYST' },
  { path: '/inbox', label: 'WhatsApp inbox', role: 'ANALYST' },
  { path: '/customers', label: 'Customers', role: 'VIEWER' },
  { path: '/support', label: 'Support requests', role: 'VIEWER' },
  { path: '/settings', label: 'Settings', role: 'VIEWER' },
  { path: '/templates', label: 'Templates', role: 'VIEWER' },
  { path: '/consent-copy', label: 'Consent copy', role: 'VIEWER' },
  { path: '/payments', label: 'Payment failures', role: 'VIEWER' },
  { path: '/ingest', label: 'Ingest health', role: 'VIEWER' },
  { path: '/exports', label: 'Exports', role: 'ANALYST' },
  { path: '/privacy', label: 'Privacy requests', role: 'OWNER' },
  { path: '/operators', label: 'Operators', role: 'OWNER' },
  { path: '/account', label: 'Account', role: 'VIEWER' },
];

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, HaltBar],
  template: `
    <div class="flex min-h-screen bg-slate-50 text-slate-900">
      <!-- Phones: the menu is a drawer behind a button, so the inbox fits a phone screen. -->
      @if (menuOpen()) {
        <div class="fixed inset-0 z-30 bg-black/40 md:hidden" (click)="menuOpen.set(false)" aria-hidden="true"></div>
      }
      <nav class="fixed inset-y-0 left-0 z-40 flex w-64 shrink-0 -translate-x-full flex-col overflow-y-auto border-r border-slate-200 bg-white
                  transition-transform md:static md:w-56 md:translate-x-0" [class.translate-x-0]="menuOpen()" aria-label="Main">
        <div class="px-5 py-4 text-lg font-semibold tracking-tight">WUMIKA <span class="text-slate-400">Engage</span></div>
        <ul class="flex-1 space-y-1 px-3">
          @for (item of nav(); track item.path) {
            <li>
              <a
                [routerLink]="item.path"
                routerLinkActive
                ariaCurrentWhenActive="page"
                (click)="menuOpen.set(false)"
                class="flex items-center justify-between rounded px-3 py-2 text-sm hover:bg-slate-100
                  aria-[current=page]:bg-slate-900 aria-[current=page]:text-white aria-[current=page]:hover:bg-slate-900"
                >{{ item.label }}
                @if (item.path === '/inbox' && unreadCount() > 0) {
                  <span class="ml-2 rounded-full bg-emerald-600 px-2 text-xs font-semibold text-white" [attr.aria-label]="unreadCount() + ' unread conversations'">{{ unreadCount() }}</span>
                }</a
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
        <header class="flex min-h-14 items-center justify-between gap-3 border-b border-slate-200 bg-white px-4 py-2 md:justify-end md:px-8" aria-label="Kill switches">
          <button type="button" class="rounded border border-slate-300 px-3 py-1 text-sm md:hidden" (click)="menuOpen.set(true)" aria-label="Open the menu">
            ☰ Menu @if (unreadCount() > 0) { <span class="ml-1 rounded-full bg-emerald-600 px-1.5 text-xs text-white">{{ unreadCount() }}</span> }
          </button>
          <app-halt-bar />
        </header>
        <main class="min-w-0 flex-1 p-4 md:p-8">
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
  readonly menuOpen = signal(false);

  /** Open WhatsApp conversations waiting for an answer, for the menu badge; refreshed every 20 s. */
  readonly unread = httpResource<{ threads: number; messages: number }>(() => (this.#tokens.has('ANALYST') ? '/api/inbox/unread' : undefined));
  readonly unreadCount = computed(() => (this.unread.hasValue() ? this.unread.value().threads : 0));

  constructor() {
    const timer = setInterval(() => { if (this.#tokens.has('ANALYST')) this.unread.reload(); }, 20000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  async signOut(): Promise<void> {
    await this.#auth.logout();
    await this.#router.navigate(['/login']);
  }
}
