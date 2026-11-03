import { HttpClient, HttpErrorResponse, httpResource } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { form, FormField, required, submit } from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { InrPipe, IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

interface Summary {
  customers: number;
  withPhone: number;
  whatsapp: number;
  buyers: number;
  reachableBuyers: number;
  sync: { lastAt: string | null; processed: number; pending: number; failed: number };
}

interface Row {
  identityId: string;
  firstName: string | null;
  city: string | null;
  orders: number;
  lastOrderAt: string | null;
  spentPaise: number | null;
  phone: string | null;
  whatsapp: 'granted' | 'withdrawn' | 'none';
}

interface Page {
  total: number;
  page: number;
  pageSize: number;
  rows: Row[];
}

export type Filter = 'all' | 'buyers' | 'whatsapp' | 'reachable';

export const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'buyers', label: 'Past buyers' },
  { key: 'whatsapp', label: 'WhatsApp permission' },
  { key: 'reachable', label: 'Reachable buyers' },
];

/** "1–50 of 1,108". */
export function range(p: Page): string {
  if (p.total === 0) return '0 of 0';
  const from = p.page * p.pageSize + 1;
  const to = Math.min(p.total, (p.page + 1) * p.pageSize);
  return `${from.toLocaleString('en-IN')}–${to.toLocaleString('en-IN')} of ${p.total.toLocaleString('en-IN')}`;
}

/**
 * The customers Engage knows from Shopify, how many it can reach, and how the
 * Shopify sync went; a list with first names and masked phones (full contact
 * details stay behind the counted reveal on a customer's page); and the exact
 * lookup by full email or phone.
 */
@Component({
  selector: 'app-customer-lookup',
  imports: [FormField, RouterLink, InrPipe, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-baseline justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Customers</h1>
        <p class="text-sm text-slate-500">Everyone Engage knows from Shopify, and who WhatsApp can reach.</p>
      </div>
      <button type="button" class="btn" (click)="summary.reload(); list.reload()">Refresh</button>
    </header>

    @if (summary.error()) {
      <app-error [error]="summary.error()" (retry)="summary.reload()" />
    } @else if (summary.hasValue()) {
      @let s = summary.value();
      <div class="mb-3 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <div class="card"><p class="text-xs text-slate-500">Shopify customers</p><p class="text-2xl font-semibold">{{ s.customers.toLocaleString('en-IN') }}</p></div>
        <div class="card"><p class="text-xs text-slate-500">With a phone</p><p class="text-2xl font-semibold">{{ s.withPhone.toLocaleString('en-IN') }}</p></div>
        <div class="card"><p class="text-xs text-slate-500">WhatsApp permission</p><p class="text-2xl font-semibold">{{ s.whatsapp.toLocaleString('en-IN') }}</p></div>
        <div class="card"><p class="text-xs text-slate-500">Past buyers</p><p class="text-2xl font-semibold">{{ s.buyers.toLocaleString('en-IN') }}</p></div>
        <div class="card"><p class="text-xs text-slate-500">Buyers reachable on WhatsApp</p><p class="text-2xl font-semibold">{{ s.reachableBuyers.toLocaleString('en-IN') }}</p></div>
      </div>
      <p class="mb-6 text-sm text-slate-600" role="status">
        Shopify sync: {{ s.sync.lastAt ? 'last run ' + (s.sync.lastAt | ist) : 'not run yet' }}
        · {{ s.sync.processed.toLocaleString('en-IN') }} customers processed
        @if (s.sync.pending > 0) { · {{ s.sync.pending }} waiting }
        @if (s.sync.failed > 0) { · <span class="text-red-700">{{ s.sync.failed }} failed</span> }
      </p>
    }

    <section class="mb-8">
      <div class="mb-3 flex flex-wrap items-center gap-2" role="tablist" aria-label="Show">
        @for (f of filters; track f.key) {
          <button type="button" role="tab" class="btn" [class.btn-primary]="filter() === f.key"
                  [attr.aria-selected]="filter() === f.key" (click)="show(f.key)">{{ f.label }}</button>
        }
      </div>
      @if (list.isLoading() && !list.hasValue()) {
        <app-loading />
      } @else if (list.error()) {
        <app-error [error]="list.error()" (retry)="list.reload()" />
      } @else if (list.hasValue()) {
        @let p = list.value();
        @if (p.rows.length === 0) {
          <app-empty text="No customers here yet." />
        } @else {
          <div class="overflow-x-auto">
            <table class="table">
              <thead><tr><th>Name</th><th>Phone</th><th>City</th><th class="num">Orders</th><th>Last order</th><th class="num">Spent</th><th>WhatsApp</th></tr></thead>
              <tbody>
                @for (r of p.rows; track r.identityId) {
                  <tr>
                    <td><a class="font-medium underline" [routerLink]="['/customers', r.identityId]">{{ r.firstName || 'No name' }}</a></td>
                    <td class="font-mono text-xs">{{ r.phone ?? '—' }}</td>
                    <td>{{ r.city ?? '—' }}</td>
                    <td class="num">{{ r.orders }}</td>
                    <td>{{ r.lastOrderAt | ist }}</td>
                    <td class="num">{{ r.spentPaise | inr }}</td>
                    <td>
                      <span class="badge" [class.badge-green]="r.whatsapp === 'granted'" [class.badge-red]="r.whatsapp === 'withdrawn'">
                        {{ r.whatsapp === 'granted' ? 'yes' : r.whatsapp === 'withdrawn' ? 'opted out' : 'no' }}
                      </span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="mt-3 flex items-center gap-3 text-sm">
            <button type="button" class="btn" [disabled]="page() === 0" (click)="page.set(page() - 1)">Previous</button>
            <span>{{ rangeOf(p) }}</span>
            <button type="button" class="btn" [disabled]="(p.page + 1) * p.pageSize >= p.total" (click)="page.set(page() + 1)">Next</button>
          </div>
        }
      }
    </section>

    <h2 class="mb-1 font-semibold">Find one customer</h2>
    <p class="mb-3 text-sm text-slate-500">Enter a customer's full email or phone number. Partial matches are not searched.</p>
    <form (submit)="find($event)" novalidate class="flex max-w-xl gap-2">
      <label class="sr-only" for="q">Email or phone</label>
      <input id="q" type="search" placeholder="priya@example.com or +91 98765 43210" [formField]="qForm.q" class="input flex-1" />
      <button type="submit" class="btn-primary" [disabled]="busy()">Find</button>
    </form>
    @if (message()) {
      <p class="mt-4 text-sm" [class.text-red-700]="!notFound()" [class.text-slate-600]="notFound()" role="status">{{ message() }}</p>
    }
  `,
})
export default class CustomerLookup {
  readonly #http = inject(HttpClient);
  readonly #router = inject(Router);

  readonly filters = FILTERS;
  readonly filter = signal<Filter>('all');
  readonly page = signal(0);

  readonly summary = httpResource<Summary>(() => '/api/customers/summary');
  readonly list = httpResource<Page>(() => ({
    url: '/api/customers/list',
    params: { filter: this.filter(), page: this.page() },
  }));

  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly notFound = signal(false);

  readonly query = signal({ q: '' });
  readonly qForm = form(this.query, (p) => required(p.q));

  show(f: Filter): void {
    this.filter.set(f);
    this.page.set(0);
  }

  rangeOf(p: Page): string {
    return range(p);
  }

  async find(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.qForm, async () => {
      this.busy.set(true);
      this.message.set(null);
      try {
        const r = await firstValueFrom(
          this.#http.get<{ identityId: string }>('/api/customers', { params: { q: this.query().q.trim() } }),
        );
        await this.#router.navigate(['/customers', r.identityId]);
      } catch (e) {
        this.notFound.set((e as HttpErrorResponse).status === 404);
        this.message.set(this.notFound() ? 'No customer has exactly that email or phone.' : problemDetail(e));
      } finally {
        this.busy.set(false);
      }
    });
  }
}
