import { HttpClient, httpResource } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CustomerView, IdentityKey, Row } from '../../core/api/models';
import { TokenService } from '../../core/auth/token.service';
import { InrPipe, IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

const KEY_LABELS: Record<string, string> = {
  email: 'Email',
  phone: 'Phone',
  shopify_customer: 'Shopify customer',
  anon: 'Browser',
  fcm_token: 'Push token',
  cart_token: 'Cart',
  checkout_token: 'Checkout',
};

/**
 * One customer: who they are, what they agreed to, and what happened to them.
 * Contact details are masked; Reveal (ANALYST) unmasks email and phone and is
 * recorded in the audit log by admin-api.
 */
@Component({
  selector: 'app-customer-view',
  imports: [RouterLink, InrPipe, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <a routerLink="/customers" class="text-sm text-slate-600 underline">← Find another customer</a>
    @if (customer.isLoading() && !customer.hasValue()) {
      <app-loading />
    } @else if (customer.error()) {
      <app-error [error]="customer.error()" [retryable]="false" />
    } @else if (customer.hasValue()) {
      @let c = customer.value();
      <header class="mb-6 mt-3 flex items-start justify-between">
        <div>
          <h1 class="text-2xl font-semibold">{{ name() || 'Customer' }}</h1>
          <p class="text-sm text-slate-500">{{ city() }} · since {{ c.profile.createdAt | ist }}</p>
        </div>
        @if (canReveal() && !revealed()) {
          <button type="button" class="btn" (click)="reveal()" [disabled]="revealing()">Reveal contact details</button>
        }
      </header>
      @if (revealError()) {
        <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ revealError() }}</p>
      }
      @if (revealed()) {
        <p class="mb-4 rounded bg-amber-50 p-3 text-sm text-amber-900" role="status">Unmasked. This view was recorded in the audit log under your name.</p>
      }

      <section class="mb-8">
        <h2 class="mb-2 font-semibold">Identifiers</h2>
        <table class="table">
          <thead><tr><th>Kind</th><th>Value</th><th>Verified</th><th>Last seen</th></tr></thead>
          <tbody>
            @for (k of keys(); track k.kind + k.value) {
              <tr>
                <td>{{ keyLabel(k.kind) }}</td>
                <td class="font-mono text-xs">{{ k.value }}</td>
                <td>{{ k.verified ? 'yes' : '—' }}</td>
                <td>{{ k.lastSeen | ist }}</td>
              </tr>
            }
          </tbody>
        </table>
      </section>

      <section class="mb-8">
        <h2 class="mb-2 font-semibold">Consent</h2>
        @if (c.consent.length === 0) {
          <app-empty text="No consent recorded." />
        } @else {
          <table class="table">
            <thead><tr><th>When</th><th>Channel</th><th>Purpose</th><th>State</th><th>How</th><th>Wording</th><th>In force</th></tr></thead>
            <tbody>
              @for (r of c.consent; track $index) {
                <tr [class.text-slate-400]="!r['inForce']">
                  <td>{{ str(r['occurredAt']) | ist }}</td>
                  <td>{{ r['channel'] }}</td>
                  <td>{{ r['purpose'] }}</td>
                  <td><span class="badge" [class.badge-green]="r['state'] === 'granted'" [class.badge-red]="r['state'] === 'withdrawn'">{{ r['state'] }}</span></td>
                  <td>{{ r['source'] }}</td>
                  <td class="font-mono text-xs">{{ r['copyVersion'] ?? '—' }}</td>
                  <td>{{ r['inForce'] ? 'yes' : '' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>

      <section class="mb-8 grid grid-cols-2 gap-8">
        <div>
          <h2 class="mb-2 font-semibold">Orders</h2>
          @if (c.orders.length === 0) {
            <app-empty text="No orders." />
          } @else {
            <table class="table">
              <thead><tr><th>Order</th><th>Placed</th><th class="num">Total</th><th>Status</th></tr></thead>
              <tbody>
                @for (o of c.orders; track o['id']) {
                  <tr>
                    <td>{{ o['orderNumber'] }}</td>
                    <td>{{ str(o['createdAt']) | ist }}</td>
                    <td class="num">{{ num(o['totalPaise']) | inr }}</td>
                    <td>{{ o['cancelledAt'] ? 'cancelled' : (o['financialStatus'] ?? '—') }}{{ num(o['refundedPaise']) ? ' · refunded ' : '' }}{{ num(o['refundedPaise']) ? (num(o['refundedPaise']) | inr) : '' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
        <div>
          <h2 class="mb-2 font-semibold">Shipments</h2>
          @if (c.shipments.length === 0) {
            <app-empty text="No shipments." />
          } @else {
            <table class="table">
              <thead><tr><th>AWB</th><th>Status</th><th>Since</th><th class="num">Failed attempts</th></tr></thead>
              <tbody>
                @for (s of c.shipments; track $index) {
                  <tr>
                    <td class="font-mono text-xs">{{ s['awb'] }}</td>
                    <td>{{ s['status'] }}</td>
                    <td>{{ str(s['statusAt']) | ist }}</td>
                    <td class="num">{{ s['ndrAttempts'] }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      </section>

      <section class="mb-8 grid grid-cols-2 gap-8">
        <div>
          <h2 class="mb-2 font-semibold">Checkouts</h2>
          @if (c.checkouts.length === 0) {
            <app-empty text="No checkouts." />
          } @else {
            <table class="table">
              <thead><tr><th>Updated</th><th class="num">Total</th><th>Last step</th><th>Completed</th></tr></thead>
              <tbody>
                @for (k of c.checkouts; track $index) {
                  <tr>
                    <td>{{ str(k['updatedAt']) | ist }}</td>
                    <td class="num">{{ num(k['totalPaise']) | inr }}</td>
                    <td>{{ k['lastStep'] ?? '—' }}</td>
                    <td>{{ str(k['completedAt']) | ist }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
        <div>
          <h2 class="mb-2 font-semibold">Payments</h2>
          @if (c.payments.length === 0) {
            <app-empty text="No payment attempts." />
          } @else {
            <table class="table">
              <thead><tr><th>When</th><th>Status</th><th class="num">Amount</th><th>Reason</th></tr></thead>
              <tbody>
                @for (p of c.payments; track $index) {
                  <tr>
                    <td>{{ str(p['receivedAt']) | ist }}</td>
                    <td>{{ p['status'] }}</td>
                    <td class="num">{{ num(p['amountPaise']) | inr }}</td>
                    <td>{{ p['errorReason'] ?? '—' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      </section>

      <section class="mb-8 grid grid-cols-2 gap-8">
        <div>
          <h2 class="mb-2 font-semibold">Push devices</h2>
          @if (c.devices.length === 0) {
            <app-empty text="No push devices." />
          } @else {
            <table class="table">
              <thead><tr><th>Device</th><th>State</th><th>Asked at</th><th>Last refresh</th></tr></thead>
              <tbody>
                @for (d of c.devices; track d['id']) {
                  <tr>
                    <td>{{ d['browser'] ?? d['platform'] }}</td>
                    <td>{{ d['state'] }}{{ d['deactivatedReason'] ? ' (' + d['deactivatedReason'] + ')' : '' }}</td>
                    <td>{{ d['permissionSource'] }}</td>
                    <td>{{ str(d['lastRefreshedAt']) | ist }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
        <div>
          <h2 class="mb-2 font-semibold">Messages</h2>
          @if (c.sends.length === 0) {
            <app-empty text="No messages sent." />
          } @else {
            <table class="table">
              <thead><tr><th>When</th><th>Message</th><th>Outcome</th></tr></thead>
              <tbody>
                @for (s of c.sends; track s['id']) {
                  <tr>
                    <td>{{ str(s['createdAt']) | ist }}</td>
                    <td>{{ s['intentKey'] ?? s['templateKey'] }} <span class="text-slate-400">· {{ s['channel'] }}</span></td>
                    <td>{{ s['status'] }}{{ s['blockReason'] ? ' · ' + s['blockReason'] : '' }}{{ s['clickedAt'] ? ' · tapped' : '' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      </section>
    }
  `,
})
export default class CustomerViewPage {
  readonly #http = inject(HttpClient);
  readonly #tokens = inject(TokenService);

  /** Route parameter (withComponentInputBinding). */
  readonly id = input.required<string>();

  readonly customer = httpResource<CustomerView>(() => `/api/customers/${encodeURIComponent(this.id())}`);
  readonly revealedKeys = signal<IdentityKey[] | null>(null);
  readonly revealing = signal(false);
  readonly revealError = signal<string | null>(null);

  readonly canReveal = computed(() => this.#tokens.has('ANALYST'));
  readonly revealed = computed(() => this.revealedKeys() !== null);

  readonly attrs = computed<Record<string, string>>(() => {
    if (!this.customer.hasValue()) return {};
    try {
      return JSON.parse(this.customer.value().profile.attrs ?? '{}') as Record<string, string>;
    } catch {
      return {};
    }
  });
  readonly name = computed(() => this.attrs()['first_name'] ?? '');
  readonly city = computed(() => [this.attrs()['city'], this.attrs()['state']].filter(Boolean).join(', ') || 'Location unknown');

  /** Masked keys, with email and phone replaced once revealed. */
  readonly keys = computed<IdentityKey[]>(() => {
    if (!this.customer.hasValue()) return [];
    const unmasked = this.revealedKeys();
    return this.customer.value().keys.map((k) => {
      const match = unmasked?.find((u) => u.kind === k.kind);
      return match && (k.kind === 'email' || k.kind === 'phone') ? { ...k, value: match.value } : k;
    });
  });

  async reveal(): Promise<void> {
    this.revealing.set(true);
    this.revealError.set(null);
    try {
      const r = await firstValueFrom(
        this.#http.post<{ keys: IdentityKey[] }>(`/api/customers/${encodeURIComponent(this.id())}/reveal`, {}),
      );
      this.revealedKeys.set(r.keys);
    } catch (e) {
      this.revealError.set(problemDetail(e, 'Could not reveal the details.'));
    } finally {
      this.revealing.set(false);
    }
  }

  keyLabel(kind: string): string {
    return KEY_LABELS[kind] ?? kind;
  }

  str(v: Row[string]): string | null {
    return v === null || v === undefined ? null : String(v);
  }

  num(v: Row[string]): number | null {
    return v === null || v === undefined ? null : Number(v);
  }
}
