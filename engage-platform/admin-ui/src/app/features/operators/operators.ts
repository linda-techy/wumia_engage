import { httpResource } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { Role } from '../../core/api/models';
import { Api } from '../../shared/api';
import { IstPipe, problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';

interface OperatorRow {
  id: string;
  email: string;
  fullName: string;
  status: string;
  mfaEnrolled: boolean;
  roles: Role[];
  lastLoginAt: string | null;
  createdAt: string;
}

const ROLES: Role[] = ['VIEWER', 'ANALYST', 'CAMPAIGN_EDIT', 'CAMPAIGN_SEND', 'CONFIG_ADMIN', 'OWNER'];

/**
 * Operators (OWNER only). Invites and resets give a one-time set-password
 * link, shown once, for the owner to pass on; the console never emails it.
 */
@Component({
  selector: 'app-operators',
  imports: [IstPipe, LoadingState, ErrorState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Operators</h1>
      <p class="text-sm text-slate-500">Who can use the console, and with which roles. Every change is audited.</p>
    </header>
    @if (link()) {
      <div class="mb-4 rounded border border-amber-300 bg-amber-50 p-3 text-sm" role="status">
        <p class="mb-1 font-medium">Set-password link for {{ linkFor() }}. Valid 72 hours, works once. Send it privately; it is shown only now.</p>
        <code class="block break-all font-mono text-xs">{{ link() }}</code>
        <button type="button" class="btn mt-2 py-1" (click)="copy()">{{ copied() ? 'Copied' : 'Copy' }}</button>
      </div>
    }
    @if (error()) { <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p> }

    @if (operators.isLoading() && !operators.hasValue()) {
      <app-loading />
    } @else if (operators.error()) {
      <app-error [error]="operators.error()" (retry)="operators.reload()" />
    } @else if (operators.hasValue()) {
      <table class="table mb-8">
        <thead><tr><th>Operator</th><th>Status</th><th>Roles</th><th>Last sign-in</th><th></th></tr></thead>
        <tbody>
          @for (o of operators.value(); track o.id) {
            <tr>
              <td><p class="font-medium">{{ o.fullName }}</p><p class="text-xs text-slate-500">{{ o.email }}{{ o.mfaEnrolled ? ' · MFA on' : '' }}</p></td>
              <td>{{ o.status }}</td>
              <td>
                @if (editing() === o.id) {
                  <div class="flex flex-wrap gap-2 text-xs">
                    @for (r of roles; track r) {
                      <label><input type="checkbox" [checked]="picked().includes(r)" (change)="toggle(r)" /> {{ r }}</label>
                    }
                  </div>
                  <button type="button" class="btn mt-2 py-1" [disabled]="busy() || picked().length === 0" (click)="saveRoles(o)">Save roles</button>
                  <button type="button" class="ml-2 text-xs underline" (click)="editing.set(null)">Cancel</button>
                } @else {
                  <span class="text-xs">{{ o.roles.join(', ') }}</span>
                  <button type="button" class="ml-2 text-xs underline" (click)="editRoles(o)">change</button>
                }
              </td>
              <td>{{ o.lastLoginAt | ist }}</td>
              <td class="whitespace-nowrap text-xs">
                <button type="button" class="underline" [disabled]="busy()" (click)="reset(o)">Reset</button>
                @if (o.status !== 'disabled') {
                  <button type="button" class="ml-2 text-red-700 underline" [disabled]="busy()" (click)="disable(o)">Disable</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }

    <section class="card max-w-2xl">
      <h2 class="mb-3 font-semibold">Invite an operator</h2>
      <form class="grid grid-cols-2 gap-4" (submit)="invite($event)">
        <label class="text-sm"><span class="mb-1 block font-medium">Email</span>
          <input type="email" class="input" [value]="email()" (input)="email.set(val($event))" /></label>
        <label class="text-sm"><span class="mb-1 block font-medium">Full name</span>
          <input class="input" [value]="fullName()" (input)="fullName.set(val($event))" /></label>
        <fieldset class="col-span-2 text-sm">
          <legend class="mb-1 font-medium">Roles</legend>
          <div class="flex flex-wrap gap-3 text-xs">
            @for (r of roles; track r) {
              <label><input type="checkbox" [checked]="newRoles().includes(r)" (change)="toggleNew(r)" /> {{ r }}</label>
            }
          </div>
          <p class="mt-1 text-xs text-slate-500">Any role above ANALYST must set up two-step sign-in before first use.</p>
        </fieldset>
        <div class="col-span-2">
          <button type="submit" class="btn-primary" [disabled]="busy() || !email().includes('@') || !fullName().trim() || newRoles().length === 0">Invite</button>
        </div>
      </form>
    </section>
  `,
})
export default class OperatorsPage {
  readonly #api = inject(Api);
  readonly roles = ROLES;
  readonly operators = httpResource<OperatorRow[]>(() => '/api/operators');

  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly link = signal<string | null>(null);
  readonly linkFor = signal('');
  readonly copied = signal(false);
  readonly editing = signal<string | null>(null);
  readonly picked = signal<Role[]>([]);
  readonly email = signal('');
  readonly fullName = signal('');
  readonly newRoles = signal<Role[]>(['VIEWER']);

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  editRoles(o: OperatorRow): void {
    this.editing.set(o.id);
    this.picked.set([...o.roles]);
  }

  toggle(r: Role): void {
    this.picked.update((p) => (p.includes(r) ? p.filter((x) => x !== r) : [...p, r]));
  }

  toggleNew(r: Role): void {
    this.newRoles.update((p) => (p.includes(r) ? p.filter((x) => x !== r) : [...p, r]));
  }

  async copy(): Promise<void> {
    await navigator.clipboard.writeText(this.link() ?? '');
    this.copied.set(true);
  }

  #showLink(path: string, who: string): void {
    this.link.set(`${location.origin}${path}`);
    this.linkFor.set(who);
    this.copied.set(false);
  }

  async #run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
      this.operators.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'That did not work.'));
    } finally {
      this.busy.set(false);
    }
  }

  invite(event: Event): Promise<void> {
    event.preventDefault();
    return this.#run(async () => {
      const r = await this.#api.post<{ setPasswordLink: string }>('/api/operators', {
        email: this.email().trim(),
        fullName: this.fullName().trim(),
        roles: this.newRoles(),
      });
      this.#showLink(r.setPasswordLink, this.email().trim());
      this.email.set('');
      this.fullName.set('');
      this.newRoles.set(['VIEWER']);
    });
  }

  saveRoles(o: OperatorRow): Promise<void> {
    return this.#run(async () => {
      await this.#api.put(`/api/operators/${o.id}/roles`, { roles: this.picked() });
      this.editing.set(null);
    });
  }

  reset(o: OperatorRow): Promise<void> {
    if (!confirm(`Reset ${o.email}? Their password and two-step sign-in stop working and every session ends.`)) return Promise.resolve();
    return this.#run(async () => {
      const r = await this.#api.post<{ setPasswordLink: string }>(`/api/operators/${o.id}/reset`, {});
      this.#showLink(r.setPasswordLink, o.email);
    });
  }

  disable(o: OperatorRow): Promise<void> {
    if (!confirm(`Disable ${o.email}? They are signed out everywhere at once.`)) return Promise.resolve();
    return this.#run(() => this.#api.post(`/api/operators/${o.id}/disable`, {}).then(() => undefined));
  }
}
