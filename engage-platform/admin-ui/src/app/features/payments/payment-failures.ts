import { httpResource } from '@angular/common/http';
import { Component } from '@angular/core';
import { PaymentFailures } from '../../core/api/models';
import { InrPipe, IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

/** Razorpay failures and how each was joined to a Shopify checkout. */
@Component({
  selector: 'app-payment-failures',
  imports: [InrPipe, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-baseline justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Payment failures</h1>
        <p class="text-sm text-slate-500">Failed Razorpay payments, last 30 days, and whether each found its shopper.</p>
      </div>
      <button type="button" class="btn" (click)="data.reload()">Refresh</button>
    </header>

    @if (data.isLoading() && !data.hasValue()) {
      <app-loading />
    } @else if (data.error()) {
      <app-error [error]="data.error()" (retry)="data.reload()" />
    } @else if (data.hasValue()) {
      <h2 class="mb-2 font-semibold">Match report (by IST day)</h2>
      @if (data.value().report.length === 0) {
        <app-empty text="No failed payments in the last 30 days." />
      } @else {
        <table class="table mb-8">
          <thead><tr><th>Day</th><th>Matched by</th><th class="num">Failures</th><th class="num">No mobile</th><th class="num">Webhook lag (s)</th></tr></thead>
          <tbody>
            @for (r of data.value().report; track r.istDay + r.matchMethod) {
              <tr>
                <td>{{ r.istDay }}</td>
                <td>{{ label(r.matchMethod) }}</td>
                <td class="num">{{ r.failures }}</td>
                <td class="num">{{ r.withoutMobile }}</td>
                <td class="num">{{ r.avgWebhookLagSeconds ?? '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }

      <h2 class="mb-2 font-semibold">Recent attempts</h2>
      @if (data.value().recent.length === 0) {
        <app-empty text="No payment attempts yet." />
      } @else {
        <table class="table">
          <thead><tr><th>Received</th><th>Payment</th><th>Status</th><th class="num">Amount</th><th>Method</th><th>Reason</th><th>Matched by</th><th>Checkout</th></tr></thead>
          <tbody>
            @for (a of data.value().recent; track a.gatewayPaymentId + a.status) {
              <tr>
                <td>{{ a.receivedAt | ist }}</td>
                <td class="font-mono text-xs">{{ a.gatewayPaymentId }}</td>
                <td><span class="badge" [class.badge-red]="a.status === 'failed'">{{ a.status }}</span></td>
                <td class="num">{{ a.amountPaise | inr }}</td>
                <td>{{ a.method ?? '—' }}</td>
                <td>{{ a.errorReason ?? '—' }}<span class="text-slate-400">{{ a.errorSource ? ' · ' + a.errorSource : '' }}</span></td>
                <td>{{ label(a.matchMethod) }}</td>
                <td>{{ a.joinedToCheckout ? 'joined' : '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class PaymentFailuresPage {
  readonly data = httpResource<PaymentFailures>(() => '/api/payments/failures');

  label(method: string | null): string {
    switch (method) {
      case 'notes_ref': return 'order reference';
      case 'phone_amount_window': return 'phone + amount';
      case 'none': return 'not matched';
      case 'unprocessed': case null: return 'not yet processed';
      default: return method;
    }
  }
}
