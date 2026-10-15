import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { TokenService } from '../../core/auth/token.service';
import { problemDetail } from '../../shared/format';

/** The word typed to confirm; erasing cannot be undone. */
export const CONFIRM_WORD = 'ERASE';

export function canErase(reason: string, confirm: string): boolean {
  return reason.trim().length >= 5 && confirm.trim() === CONFIRM_WORD;
}

/**
 * DPDP erasure of one customer (OWNER). Deletes them, everything merged into
 * them and every copy of their phone and email; order totals and message
 * spend stay, attached to no one.
 */
@Component({
  selector: 'app-erase-customer',
  template: `
    @if (isOwner()) {
      <section class="mb-8 rounded border border-red-200 bg-red-50 p-4" aria-labelledby="erase-h">
        <h2 id="erase-h" class="font-semibold text-red-900">Erase this customer</h2>
        <p class="mb-3 text-sm text-red-900">
          For a deletion request under the DPDP Act. Removes their contact details, consents, devices, journeys and
          message history from Engage for good. Order totals and messaging spend stay, attached to no one.
          Delete them in Shopify too.
        </p>
        @if (!open()) {
          <button type="button" class="btn" (click)="open.set(true)">Erase…</button>
        } @else {
          <div class="flex flex-wrap items-end gap-3">
            <label class="flex flex-col text-sm">
              <span>Reason (who asked, how, when)</span>
              <input class="input w-96" maxlength="500" placeholder="e.g. Customer emailed support on 15 Oct asking to be deleted"
                     [value]="reason()" (input)="reason.set(value($event))" />
            </label>
            <label class="flex flex-col text-sm">
              <span>Type {{ word }} to confirm</span>
              <input class="input w-32 font-mono" [value]="confirm()" (input)="confirm.set(value($event))" />
            </label>
            <button type="button" class="btn bg-red-700 text-white" (click)="erase()" [disabled]="busy() || !ready()">
              Erase for good
            </button>
            <button type="button" class="btn" (click)="open.set(false)" [disabled]="busy()">Cancel</button>
          </div>
        }
        @if (error()) { <p class="mt-2 text-sm text-red-800" role="alert">{{ error() }}</p> }
      </section>
    }
  `,
})
export class EraseCustomer {
  readonly #http = inject(HttpClient);
  readonly #tokens = inject(TokenService);
  readonly #router = inject(Router);

  readonly identityId = input.required<string>();
  readonly word = CONFIRM_WORD;
  readonly isOwner = computed(() => this.#tokens.has('OWNER'));
  readonly open = signal(false);
  readonly reason = signal('');
  readonly confirm = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly ready = computed(() => canErase(this.reason(), this.confirm()));

  value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  async erase(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.#http.post(`/api/customers/${encodeURIComponent(this.identityId())}/erase`,
        { reason: this.reason().trim() }));
      await this.#router.navigate(['/privacy'], { queryParams: { status: 'done' } });
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not erase the customer.'));
    } finally {
      this.busy.set(false);
    }
  }
}
