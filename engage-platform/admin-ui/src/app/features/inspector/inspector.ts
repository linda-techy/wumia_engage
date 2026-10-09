import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../shared/api';
import { InrPipe, IstPipe, problemDetail } from '../../shared/format';
import { EmptyState } from '../../shared/states';

interface Attempt {
  stepIndex: number;
  channel: string;
  result: string;
  reason: string | null;
  sendId: number | null;
  createdAt: string;
}

interface Run {
  id: number;
  intentKey: string;
  subjectKey: string;
  status: string;
  outcome: string | null;
  stepIndex: number;
  nextStepAt: string;
  createdAt: string;
  attempts: Attempt[];
}

interface Send {
  id: number;
  channel: string;
  category: string;
  templateKey: string;
  intentKey: string | null;
  status: string;
  decision: Record<string, unknown> | null;
  failedReason: string | null;
  costPaise: number | null;
  createdAt: string;
  sentAt: string | null;
  deliveredAt: string | null;
  clickedAt: string | null;
}

interface Inspection {
  identityId: string;
  phone: string;
  emails: string[];
  runs: Run[];
  sends: Send[];
}

/**
 * Journey inspector (phase-6 §2): "why did, or didn't, this customer get a
 * message". Exact phone number and a reason; every lookup is logged and
 * counts toward the 50-a-day limit. The number goes in the request body,
 * never the URL.
 */
@Component({
  selector: 'app-inspector',
  imports: [RouterLink, IstPipe, InrPipe, EmptyState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Journey inspector</h1>
      <p class="text-sm text-slate-500">Every journey run, step and policy decision for one customer. Each lookup is logged with your reason.</p>
    </header>
    <form class="mb-6 flex max-w-3xl flex-wrap items-end gap-3" (submit)="inspect($event)">
      <label class="text-sm">
        <span class="mb-1 block font-medium">Mobile number</span>
        <input class="input w-48 font-mono" inputmode="tel" autocomplete="off" placeholder="98765 43210" [value]="phone()" (input)="phone.set(val($event))" />
      </label>
      <label class="flex-1 text-sm">
        <span class="mb-1 block font-medium">Why</span>
        <input class="input" placeholder="Complaint #142: no cart reminder" [value]="reason()" (input)="reason.set(val($event))" />
      </label>
      <button type="submit" class="btn-primary" [disabled]="busy() || phone().trim().length < 10 || reason().trim().length < 5">Look up</button>
    </form>
    @if (error()) {
      <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p>
    }
    @if (result(); as r) {
      <p class="mb-6 text-sm">
        <span class="font-mono">{{ r.phone }}</span>
        @for (e of r.emails; track e) { · <span class="font-mono">{{ e }}</span> }
        · <a class="underline" [routerLink]="['/customers', r.identityId]">customer view</a>
      </p>

      <h2 class="mb-2 font-semibold">Journey runs</h2>
      @for (run of r.runs; track run.id) {
        <div class="card mb-3">
          <p class="text-sm"><strong class="font-mono">{{ run.intentKey }}</strong> · {{ run.status }}{{ run.outcome ? ' (' + run.outcome + ')' : '' }}
            <span class="text-xs text-slate-500">· started {{ run.createdAt | ist }} · subject {{ run.subjectKey }}</span></p>
          <ol class="mt-2 space-y-1 text-sm">
            @for (a of run.attempts; track $index) {
              <li>Step {{ a.stepIndex + 1 }} · {{ a.channel }} · <strong>{{ a.result }}</strong>{{ a.reason ? ': ' + a.reason : '' }}
                <span class="text-xs text-slate-500">{{ a.createdAt | ist }}</span></li>
            } @empty {
              <li class="text-slate-500">No step has run yet. Next at {{ run.nextStepAt | ist }}.</li>
            }
          </ol>
        </div>
      } @empty {
        <app-empty text="No journey has run for this customer." />
      }

      <h2 class="mb-2 mt-6 font-semibold">Sends and decisions</h2>
      @if (r.sends.length === 0) {
        <app-empty text="No send attempts." />
      } @else {
        <table class="table">
          <thead><tr><th>When</th><th>Channel</th><th>Template</th><th>Why</th><th>Outcome</th><th>Decision</th><th class="num">Cost</th></tr></thead>
          <tbody>
            @for (s of r.sends; track s.id) {
              <tr>
                <td>{{ s.createdAt | ist }}</td>
                <td>{{ s.channel }} · {{ s.category }}</td>
                <td class="font-mono text-xs">{{ s.templateKey }}</td>
                <td class="font-mono text-xs">{{ s.intentKey }}</td>
                <td>{{ s.status }}{{ s.failedReason ? ' (' + s.failedReason + ')' : '' }}@if (s.clickedAt) { · clicked }</td>
                <td class="font-mono text-xs">{{ decision(s) }}</td>
                <td class="num">{{ s.costPaise | inr }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class InspectorPage {
  readonly #api = inject(Api);
  readonly phone = signal('');
  readonly reason = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly result = signal<Inspection | null>(null);

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  decision(s: Send): string {
    const d = s.decision ?? {};
    return typeof d['reason'] === 'string' ? (d['reason'] as string) : Object.keys(d).length ? JSON.stringify(d) : '—';
  }

  async inspect(event: Event): Promise<void> {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    try {
      this.result.set(await this.#api.post<Inspection>('/api/inspector', { phone: this.phone().trim(), reason: this.reason().trim() }));
    } catch (e) {
      this.error.set(problemDetail(e, 'Lookup failed.'));
    } finally {
      this.busy.set(false);
    }
  }
}
