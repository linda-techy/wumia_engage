import { httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { TokenService } from '../../core/auth/token.service';
import { Api, istInputToIso } from '../../shared/api';
import { IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';
import { ConfigKey, ConfigProposal, Draft, draftFor, parseValue, showValue } from './config-model';

const CHANNELS = ['whatsapp', 'push', 'email', 'sms', 'rcs'];

/**
 * Settings, generated from GET /api/config (02-config-and-settings): each
 * key's control comes from its value type. SAFE and GUARDED changes apply at
 * once; CRITICAL ones show the change and become a proposal that a second
 * CONFIG_ADMIN approves. Every change needs a reason. Kill switches live in
 * the header, not here.
 */
@Component({
  selector: 'app-settings',
  imports: [IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Settings</h1>
      <p class="text-sm text-slate-500">Policy settings with their history. Changes to spend or compliance need a second admin.</p>
    </header>
    @if (notice()) {
      <p class="mb-4 rounded bg-emerald-50 p-3 text-sm text-emerald-800" role="status">{{ notice() }}</p>
    }
    @if (error()) {
      <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p>
    }

    @if (config.isLoading() && !config.hasValue()) {
      <app-loading />
    } @else if (config.error() && !config.hasValue()) {
      <app-error [error]="config.error()" (retry)="config.reload()" />
    } @else if (config.hasValue()) {
      <section class="mb-8">
        <h2 class="mb-3 font-semibold">Waiting for approval</h2>
        @if (pending().length === 0) {
          <app-empty text="No proposals waiting." />
        } @else {
          <table class="table">
            <thead><tr><th>Setting</th><th>Change</th><th>From</th><th>Why</th><th>Proposed by</th><th></th></tr></thead>
            <tbody>
              @for (p of pending(); track p.proposal.proposalId) {
                <tr>
                  <td>{{ p.key.label }} <span class="font-mono text-xs text-slate-500">{{ p.proposal.selector }}</span></td>
                  <td class="font-mono text-xs">{{ show(current(p.key, p.proposal.selector)) }} → <strong>{{ show(p.proposal.value) }}</strong></td>
                  <td>{{ p.proposal.effectiveFrom ? (p.proposal.effectiveFrom | ist) : 'on approval' }}@if (p.proposal.effectiveTo) { – {{ p.proposal.effectiveTo | ist }} }</td>
                  <td class="max-w-xs">{{ p.proposal.reason }}</td>
                  <td>{{ p.proposal.proposedBy }}</td>
                  <td class="whitespace-nowrap">
                    @if (mine(p.proposal)) {
                      <button type="button" class="btn py-1" [disabled]="busy()" (click)="decide(p.proposal, 'withdraw')">Withdraw</button>
                    } @else if (canEdit()) {
                      <button type="button" class="btn-primary py-1" [disabled]="busy()" (click)="decide(p.proposal, 'approve')">Approve</button>
                      <button type="button" class="btn ml-1 py-1" [disabled]="busy()" (click)="decide(p.proposal, 'reject')">Reject</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>

      <section>
        <table class="table">
          <thead><tr><th>Setting</th><th>Risk</th><th>In force</th><th>Scheduled</th><th></th></tr></thead>
          <tbody>
            @for (k of keys(); track k.key) {
              <tr>
                <td>
                  <p class="font-medium">{{ k.label }}</p>
                  <p class="font-mono text-xs text-slate-500">{{ k.key }}</p>
                  @if (k.helpText) { <p class="mt-1 max-w-md text-xs text-slate-500">{{ k.helpText }}</p> }
                </td>
                <td><span class="badge" [class.badge-red]="k.risk === 'CRITICAL'">{{ k.risk }}</span></td>
                <td class="text-sm">
                  @for (v of k.inForce; track v.versionId) {
                    <p><span class="mr-1 font-mono text-xs text-slate-500">{{ v.selector }}</span><strong>{{ show(v.value) }}</strong>
                      <span class="ml-1 text-xs text-slate-500" [title]="v.reason">· {{ v.effectiveFrom | ist }}{{ v.changedBy ? ' · ' + v.changedBy : '' }}{{ v.effectiveTo ? ' · until ' : '' }}{{ v.effectiveTo ? (v.effectiveTo | ist) : '' }}</span></p>
                  } @empty {
                    <p><strong>{{ show(k.defaultValue) }}</strong> <span class="ml-1 text-xs text-slate-500">default</span></p>
                  }
                </td>
                <td class="text-xs">
                  @for (v of k.scheduled; track v.versionId) {
                    <p>{{ v.selector }} → {{ show(v.value) }} from {{ v.effectiveFrom | ist }}</p>
                  }
                </td>
                <td>
                  @if (canEdit() && !k.key.startsWith('halt.')) {
                    <button type="button" class="btn py-1" (click)="edit(k)">Change…</button>
                  }
                </td>
              </tr>
              @if (editing()?.key === k.key) {
                <tr>
                  <td colspan="5" class="bg-slate-50">
                    <form class="grid max-w-3xl grid-cols-2 gap-4 py-2" (submit)="save($event, k)">
                      @if (k.scope !== 'GLOBAL') {
                        <label class="text-sm">
                          <span class="mb-1 block font-medium">Applies to</span>
                          @if (k.scope === 'CHANNEL') {
                            <select class="input" [value]="selector()" (change)="setSelector(k, val($event))">
                              <option value="*">every channel</option>
                              @for (c of channels; track c) { <option [value]="c">{{ c }}</option> }
                            </select>
                          } @else {
                            <input class="input font-mono" placeholder="* or a key" [value]="selector()" (input)="setSelector(k, val($event))" />
                          }
                        </label>
                      }
                      <label class="text-sm">
                        <span class="mb-1 block font-medium">New value</span>
                        @switch (k.valueType) {
                          @case ('BOOL') {
                            <input type="checkbox" [checked]="draft().flag" (change)="patch({ flag: checked($event) })" /> on
                          }
                          @case ('TIME_RANGE') {
                            <span class="flex gap-2">
                              <input type="time" class="input" [value]="draft().from" (input)="patch({ from: val($event) })" aria-label="From (IST)" />
                              <input type="time" class="input" [value]="draft().to" (input)="patch({ to: val($event) })" aria-label="To (IST)" />
                            </span>
                          }
                          @case ('ENUM') {
                            <select class="input" [value]="draft().text" (change)="patch({ text: val($event) })">
                              @for (o of k.jsonSchema?.enum ?? []; track o) { <option [value]="o">{{ o }}</option> }
                            </select>
                          }
                          @case ('JSON') {
                            <textarea rows="3" class="input font-mono" [value]="draft().text" (input)="patch({ text: val($event) })"></textarea>
                          }
                          @default {
                            <input class="input" [attr.inputmode]="k.valueType === 'STRING' ? null : 'decimal'" [value]="draft().text" (input)="patch({ text: val($event) })" />
                          }
                        }
                        @if (k.jsonSchema?.minimum !== undefined || k.jsonSchema?.maximum !== undefined) {
                          <span class="mt-1 block text-xs text-slate-500">Allowed: {{ k.jsonSchema?.minimum ?? '…' }} to {{ k.jsonSchema?.maximum ?? '…' }}</span>
                        }
                      </label>
                      <label class="text-sm">
                        <span class="mb-1 block font-medium">Starts (IST, optional)</span>
                        <input type="datetime-local" class="input" [value]="from()" (input)="from.set(val($event))" />
                      </label>
                      <label class="text-sm">
                        <span class="mb-1 block font-medium">Ends and reverts (IST, optional)</span>
                        <input type="datetime-local" class="input" [value]="to()" (input)="to.set(val($event))" />
                      </label>
                      <label class="col-span-2 text-sm">
                        <span class="mb-1 block font-medium">Why (10 characters or more)</span>
                        <input class="input" [value]="reason()" (input)="reason.set(val($event))" />
                      </label>
                      @if (parsed(); as p) {
                        @if (!p.ok) {
                          <p class="col-span-2 text-sm text-red-700">{{ p.error }}</p>
                        } @else if (k.risk === 'CRITICAL') {
                          <p class="col-span-2 rounded bg-amber-50 p-3 text-sm text-amber-900">
                            {{ k.label }} ({{ selector() }}): <span class="font-mono">{{ show(current(k, selector())) }}</span> →
                            <strong class="font-mono">{{ show(p.value) }}</strong>. This is a proposal: it changes nothing until another admin approves it.
                          </p>
                        }
                      }
                      <div class="col-span-2 flex gap-2">
                        <button type="submit" class="btn-primary" [disabled]="busy() || !parsed().ok || reason().trim().length < 10">
                          {{ k.risk === 'CRITICAL' ? 'Propose' : 'Save' }}
                        </button>
                        <button type="button" class="btn" (click)="editing.set(null)">Cancel</button>
                      </div>
                    </form>
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export default class SettingsPage {
  readonly #api = inject(Api);
  readonly #tokens = inject(TokenService);

  readonly channels = CHANNELS;
  readonly config = httpResource<ConfigKey[]>(() => '/api/config');
  readonly keys = computed(() => (this.config.hasValue() ? this.config.value() : []));
  readonly pending = computed(() => this.keys().flatMap((key) => key.pending.map((proposal) => ({ key, proposal }))));
  readonly canEdit = computed(() => this.#tokens.has('CONFIG_ADMIN'));

  readonly editing = signal<ConfigKey | null>(null);
  readonly selector = signal('*');
  readonly draft = signal<Draft>({ text: '', flag: false, from: '', to: '' });
  readonly from = signal('');
  readonly to = signal('');
  readonly reason = signal('');
  readonly busy = signal(false);
  readonly notice = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly parsed = computed(() => {
    const k = this.editing();
    return k ? parseValue(k, this.draft()) : ({ ok: false, error: '' } as const);
  });

  readonly show = showValue;

  current(k: ConfigKey, selector: string): unknown {
    return k.inForce.find((v) => v.selector === selector)?.value ?? k.inForce.find((v) => v.selector === '*')?.value ?? k.defaultValue;
  }

  mine(p: ConfigProposal): boolean {
    return p.proposedBy === this.#tokens.operator()?.email;
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  patch(p: Partial<Draft>): void {
    this.draft.update((d) => ({ ...d, ...p }));
  }

  edit(k: ConfigKey): void {
    this.editing.set(k);
    this.setSelector(k, '*');
    this.from.set('');
    this.to.set('');
    this.reason.set('');
    this.notice.set(null);
    this.error.set(null);
  }

  setSelector(k: ConfigKey, s: string): void {
    this.selector.set(s);
    this.draft.set(draftFor(k, s));
  }

  async save(event: Event, k: ConfigKey): Promise<void> {
    event.preventDefault();
    const p = this.parsed();
    if (!p.ok) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const r = await this.#api.post<{ proposalId?: number }>(`/api/config/${encodeURIComponent(k.key)}`, {
        selector: this.selector() || '*',
        value: p.value,
        effectiveFrom: istInputToIso(this.from()),
        effectiveTo: istInputToIso(this.to()),
        reason: this.reason().trim(),
      });
      this.editing.set(null);
      this.notice.set(r.proposalId ? `Proposed. Another admin must approve proposal ${r.proposalId}.` : `${k.label} changed.`);
      this.config.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not save the change.'));
    } finally {
      this.busy.set(false);
    }
  }

  async decide(p: ConfigProposal, action: 'approve' | 'reject' | 'withdraw'): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      if (action === 'withdraw') await this.#api.delete(`/api/config/proposals/${p.proposalId}`);
      else await this.#api.post(`/api/config/proposals/${p.proposalId}/${action}`, {});
      this.notice.set(`Proposal ${p.proposalId} ${action === 'approve' ? 'approved' : action === 'reject' ? 'rejected' : 'withdrawn'}.`);
      this.config.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not record the decision.'));
    } finally {
      this.busy.set(false);
    }
  }
}
