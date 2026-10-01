import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { form, FormField, required, submit } from '@angular/forms/signals';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { problemDetail } from '../../shared/format';

/**
 * Find one customer by their full email or phone. No partial search and no
 * list, by design: the console must not be a way to export the customer base.
 */
@Component({
  selector: 'app-customer-lookup',
  imports: [FormField],
  template: `
    <h1 class="mb-1 text-2xl font-semibold">Customers</h1>
    <p class="mb-6 text-sm text-slate-500">Enter a customer's full email or phone number. Partial matches are not searched.</p>
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

  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly notFound = signal(false);

  readonly query = signal({ q: '' });
  readonly qForm = form(this.query, (p) => required(p.q));

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
