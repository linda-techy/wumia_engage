import { HttpClient, httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, effect, ElementRef, inject, input, OnDestroy, signal, untracked, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api } from '../../shared/api';
import { InrPipe, problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { fillName, InboxOrder, InboxThread, InboxThreadRow, orderStatus, QuickReply, refusalText, trackingText, windowState } from './inbox-model';
import { StaffAlerts } from './staff-alerts';

/**
 * A customer's photo, voice note or document. The file comes through admin-api
 * (which holds the WhatsApp token), so it is fetched with the operator's
 * session and shown from a blob URL.
 */
@Component({
  selector: 'app-inbox-media',
  template: `
    @if (url()) {
      @if (isImage()) {
        <a [href]="url()" target="_blank" rel="noopener"><img [src]="url()" alt="Photo from the customer" class="max-h-64 rounded" /></a>
      } @else if (isAudio()) {
        <audio [src]="url()" controls class="w-60"></audio>
      } @else if (isVideo()) {
        <video [src]="url()" controls class="max-h-64 rounded"></video>
      } @else {
        <a [href]="url()" [attr.download]="name() || 'file'" class="underline">📄 {{ name() || 'Download the file' }}</a>
      }
    } @else if (failed()) {
      <span class="text-xs text-red-700">{{ failed() }}</span>
    } @else {
      <span class="text-xs text-slate-500">Loading the file…</span>
    }
  `,
})
export class InboxMedia implements OnDestroy {
  readonly #http = inject(HttpClient);
  readonly messageId = input.required<number>();
  readonly mime = input<string | null>(null);
  readonly name = input<string | null>(null);
  readonly url = signal<string | null>(null);
  readonly failed = signal<string | null>(null);
  readonly isImage = computed(() => (this.mime() ?? '').startsWith('image/'));
  readonly isAudio = computed(() => (this.mime() ?? '').startsWith('audio/'));
  readonly isVideo = computed(() => (this.mime() ?? '').startsWith('video/'));

  constructor() {
    effect(() => {
      const id = this.messageId();
      untracked(async () => {
        try {
          const blob = await firstValueFrom(this.#http.get(`/api/inbox/media/${id}`, { responseType: 'blob' }));
          this.url.set(URL.createObjectURL(blob));
        } catch (e) {
          this.failed.set(problemDetail(e, 'The file is no longer available.'));
        }
      });
    });
  }

  ngOnDestroy(): void {
    const u = this.url();
    if (u) URL.revokeObjectURL(u);
  }
}

/**
 * The WhatsApp inbox (V49): conversations on the left (or alone on a phone),
 * the chat on the right. Inside the 24 h window the customer opened, staff
 * reply freely (no Meta charge); after it, the approved follow-up template
 * asks them to write again. Engage's automated messages appear in the thread.
 */
@Component({
  selector: 'app-inbox',
  imports: [RouterLink, LoadingState, ErrorState, InboxMedia, InrPipe, StaffAlerts],
  template: `
    <div class="flex h-[calc(100vh-7rem)] min-h-[28rem] gap-4 md:h-[calc(100vh-9rem)]">
      <!-- Conversations: hidden on a phone while a chat is open. -->
      <section class="w-full flex-col md:flex md:w-80 md:shrink-0" [class.hidden]="!!id()" [class.flex]="!id()" aria-label="Conversations">
        <h1 class="mb-1 text-xl font-semibold">WhatsApp inbox</h1>
        <app-staff-alerts class="mb-2 block" />
        <div class="mb-2 flex gap-1 text-sm" role="tablist">
          @for (s of statuses; track s[0]) {
            <button type="button" role="tab" class="rounded px-3 py-1" [class.bg-slate-900]="status() === s[0]" [class.text-white]="status() === s[0]"
                    [attr.aria-selected]="status() === s[0]" (click)="status.set(s[0])">{{ s[1] }}</button>
          }
        </div>
        <input class="input mb-2" placeholder="Search a name or number" [value]="q()" (input)="q.set(val($event))" aria-label="Search conversations" />
        <div class="min-h-0 flex-1 overflow-y-auto rounded border border-slate-200 bg-white">
          @if (threads.isLoading() && !threads.hasValue()) {
            <app-loading />
          } @else if (threads.error()) {
            <app-error [error]="threads.error()" (retry)="threads.reload()" />
          } @else {
            @for (t of threads.value() ?? []; track t.identityId) {
              <a [routerLink]="['/inbox', t.identityId]" class="block border-b border-slate-100 px-3 py-2 hover:bg-slate-50"
                 [class.bg-emerald-50]="t.identityId === id()">
                <div class="flex items-center justify-between gap-2">
                  <span class="truncate font-medium">{{ t.name || display(t.phone) }}</span>
                  <span class="shrink-0 text-xs text-slate-500">{{ when(t.lastMessageAt) }}</span>
                </div>
                <div class="flex items-center justify-between gap-2">
                  <span class="truncate text-sm text-slate-600">{{ t.lastPreview }}</span>
                  @if (t.unread > 0) { <span class="shrink-0 rounded-full bg-emerald-600 px-2 text-xs font-semibold text-white">{{ t.unread }}</span> }
                </div>
                @if (windowOf(t).open) { <span class="text-[11px] text-emerald-700">Free reply · {{ windowOf(t).left }} left</span> }
              </a>
            } @empty {
              <p class="p-4 text-sm text-slate-500">{{ status() === 'open' ? 'No open conversations. New WhatsApp messages appear here.' : 'Nothing here.' }}</p>
            }
          }
        </div>
      </section>

      <!-- The chat. -->
      <section class="min-w-0 flex-1 flex-col md:flex" [class.hidden]="!id()" [class.flex]="!!id()" aria-label="Conversation">
        @if (!id()) {
          <p class="m-auto text-sm text-slate-500">Pick a conversation.</p>
        } @else if (thread.isLoading() && !thread.hasValue()) {
          <app-loading />
        } @else if (thread.error() && !thread.hasValue()) {
          <app-error [error]="thread.error()" [retryable]="false" />
        } @else if (t(); as t) {
          <header class="mb-2 flex flex-wrap items-start justify-between gap-2 rounded border border-slate-200 bg-white p-3">
            <div class="min-w-0">
              <a routerLink="/inbox" class="mr-2 text-sm underline md:hidden">← All</a>
              <span class="font-semibold">{{ t.name || display(t.phone) }}</span>
              <span class="ml-2 text-sm text-slate-600">{{ display(t.phone) }}</span>
              @if (t.email) { <span class="ml-2 text-sm text-slate-500">· {{ t.email }}</span> }
              @if (t.orders.length) {
                <ul class="mt-1 space-y-0.5 text-xs text-slate-600" aria-label="Recent orders">
                  @for (o of t.orders; track o.orderNumber) {
                    <li>
                      {{ o.orderNumber }} · {{ o.totalPaise | inr }} · <span class="font-medium">{{ statusOf(o) }}</span>
                      @if (o.carrier) { · {{ o.carrier }} }
                      @if (o.trackingUrl) { · <a [href]="o.trackingUrl" target="_blank" rel="noopener" class="underline">track</a> }
                      @if (win().open) { · <button type="button" class="underline" (click)="insert(tracking(o))">insert in reply</button> }
                    </li>
                  }
                </ul>
              } @else {
                <p class="mt-1 text-xs text-slate-500">No orders on this number.</p>
              }
            </div>
            <div class="flex gap-2">
              @if (t.status === 'open') {
                <button type="button" class="btn py-1" (click)="setStatus('done')" [disabled]="busy()">Mark done</button>
              } @else {
                <button type="button" class="btn py-1" (click)="setStatus('open')" [disabled]="busy()">Reopen</button>
              }
            </div>
          </header>

          <div #scroller class="min-h-0 flex-1 space-y-2 overflow-y-auto rounded border border-slate-200 bg-[#efeae2] p-3">
            @for (m of t.messages; track m.id) {
              <div class="flex" [class.justify-end]="m.direction === 'out'">
                <div class="max-w-[85%] rounded-lg px-3 py-2 text-sm shadow-sm"
                     [class.bg-white]="m.direction === 'in'" [class.bg-[#d9fdd3]]="m.direction === 'out'">
                  @if (m.status === 'failed' && !m.wamid) { <p class="mb-1 text-[11px] font-medium text-red-700">Not sent: {{ refusal(m.failedReason) }}</p> }
                  @if (m.automated) { <p class="mb-1 text-[11px] font-medium text-slate-500">Automated · {{ m.templateKey }}</p> }
                  @else if (m.direction === 'out' && m.templateKey) { <p class="mb-1 text-[11px] font-medium text-slate-500">Template · {{ m.templateKey }}</p> }
                  @if (m.hasMedia) { <app-inbox-media [messageId]="m.id" [mime]="m.mediaMime" [name]="m.mediaName" /> }
                  @if (m.body) { <p class="whitespace-pre-wrap break-words">{{ m.body }}</p> }
                  <p class="mt-1 text-right text-[11px] text-slate-500">
                    {{ m.sentBy ? m.sentBy + ' · ' : '' }}{{ time(m.at) }}
                    @if (m.direction === 'out') {
                      @switch (m.status) {
                        @case ('read') { <span class="text-sky-600" aria-label="read">✓✓</span> }
                        @case ('delivered') { <span aria-label="delivered">✓✓</span> }
                        @case ('failed') { <span class="text-red-700">failed {{ m.failedReason }}</span> }
                        @default { <span aria-label="sent">✓</span> }
                      }
                    }
                  </p>
                </div>
              </div>
            } @empty {
              <p class="text-center text-sm text-slate-500">No messages yet.</p>
            }
            @for (p of pendingFor(t); track p.at) {
              <div class="flex justify-end">
                <div class="max-w-[85%] rounded-lg bg-[#d9fdd3] px-3 py-2 text-sm opacity-70 shadow-sm">
                  <p class="whitespace-pre-wrap break-words">{{ p.text }}</p>
                  <p class="mt-1 text-right text-[11px] text-slate-500">Sending… 🕓</p>
                </div>
              </div>
            }
          </div>

          @if (error()) { <p class="mt-2 rounded bg-red-50 p-2 text-sm text-red-800" role="alert">{{ error() }}</p> }
          @if (info()) { <p class="mt-2 rounded bg-sky-50 p-2 text-sm text-sky-900" role="status">{{ info() }}</p> }
          <footer class="mt-2">
            @if (win().open) {
              @if (showQuick()) {
                <div class="mb-2 max-h-64 overflow-y-auto rounded border border-slate-200 bg-white p-2" aria-label="Saved answers">
                  <div class="mb-1 flex items-center justify-between">
                    <span class="text-xs font-medium text-slate-600">Saved answers</span>
                    <button type="button" class="text-xs underline" (click)="managing.set(!managing()); editing.set(null)">{{ managing() ? 'Done editing' : 'Edit' }}</button>
                  </div>
                  @if (!managing()) {
                    <div class="flex flex-wrap gap-1">
                      @for (q of quick.value() ?? []; track q.id) {
                        <button type="button" class="rounded-full border border-slate-300 px-3 py-1 text-xs hover:bg-slate-100" [title]="q.body"
                                (click)="insert(fill(q.body, t.name)); showQuick.set(false)">{{ q.title }}</button>
                      } @empty { <p class="text-xs text-slate-500">No saved answers yet. Tap Edit to add one.</p> }
                    </div>
                  } @else {
                    @for (q of quick.value() ?? []; track q.id) {
                      <div class="flex items-center justify-between border-b border-slate-100 py-1 text-xs">
                        <span class="truncate">{{ q.title }}</span>
                        <span class="shrink-0">
                          <button type="button" class="underline" (click)="editing.set({ id: q.id, title: q.title, body: q.body, sort: q.sort })">Edit</button>
                          <button type="button" class="ml-2 text-red-700 underline" (click)="deleteQuick(q)">Delete</button>
                        </span>
                      </div>
                    }
                    @if (editing(); as e) {
                      <div class="mt-2 space-y-1">
                        <input class="input text-sm" maxlength="40" placeholder="Title, e.g. Delivery time" [value]="e.title"
                               (input)="editing.set({ id: e.id, title: val($event), body: e.body, sort: e.sort })" aria-label="Title" />
                        <textarea class="input text-sm" rows="3" maxlength="1024" placeholder="The answer. {name} becomes the customer's first name."
                                  [value]="e.body" (input)="editing.set({ id: e.id, title: e.title, body: val($event), sort: e.sort })" aria-label="Answer"></textarea>
                        <button type="button" class="btn-primary py-1 text-xs" [disabled]="busy() || !e.title.trim() || !e.body.trim()" (click)="saveQuick(e)">Save</button>
                        <button type="button" class="btn ml-1 py-1 text-xs" (click)="editing.set(null)">Cancel</button>
                      </div>
                    } @else {
                      <button type="button" class="mt-2 text-xs underline" (click)="editing.set({ id: null, title: '', body: '', sort: 100 })">+ Add a saved answer</button>
                    }
                  }
                </div>
              }
              <form class="flex items-end gap-2" (submit)="reply($event)">
                <button type="button" class="btn px-2" (click)="showQuick.set(!showQuick())" [attr.aria-expanded]="showQuick()"
                        title="Saved answers" aria-label="Saved answers">⚡</button>
                <textarea class="input min-h-[2.75rem] flex-1 resize-y" rows="2" [value]="draft()" (input)="draft.set(val($event))"
                          (keydown.enter)="enterToSend($event)" placeholder="Type a reply" aria-label="Reply" maxlength="4096"></textarea>
                <button type="submit" class="btn-primary" [disabled]="busy() || !draft().trim()">Send</button>
              </form>
              <p class="mt-1 text-xs text-slate-500">Free reply window: {{ win().left }} left (24 h from the customer's last message). Enter sends, Shift+Enter adds a line.</p>
            } @else {
              <div class="rounded border border-amber-300 bg-amber-50 p-3 text-sm">
                <p>The 24-hour window has closed, so WhatsApp only allows an approved template now. When the customer answers, you can reply freely again.</p>
                @if (t.followUps.length) {
                  <button type="button" class="btn-primary mt-2" [disabled]="busy()" (click)="followUp(t.followUps[0].key)">Send the follow-up template</button>
                  <p class="mt-1 text-xs text-slate-600">"Hi {{ t.name || 'there' }}, this is WUMIKA customer care following up on your message. Reply here and we will help you."</p>
                } @else {
                  <p class="mt-2 text-xs text-slate-600">The follow-up template is waiting for Meta's approval.</p>
                }
              </div>
            }
          </footer>
        }
      </section>
    </div>
  `,
})
export default class InboxPage {
  readonly #api = inject(Api);
  readonly #router = inject(Router);

  /** Route parameter: the open conversation. */
  readonly id = input<string>();

  readonly statuses: [string, string][] = [['open', 'Open'], ['done', 'Done'], ['all', 'All']];
  readonly status = signal('open');
  readonly q = signal('');
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly now = signal(Date.now());
  readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  readonly showQuick = signal(false);
  readonly managing = signal(false);
  readonly editing = signal<{ id: number | null; title: string; body: string; sort: number } | null>(null);
  readonly quick = httpResource<QuickReply[]>(() => '/api/inbox/quick-replies');
  /** Replies the worker has not sent yet (the API queues them); shown until they appear in the thread. */
  readonly pending = signal<{ id: string; text: string; at: number }[]>([]);
  readonly info = signal<string | null>(null);

  readonly threads = httpResource<InboxThreadRow[]>(() =>
    `/api/inbox?status=${this.status()}${this.q().trim() ? '&q=' + encodeURIComponent(this.q().trim()) : ''}`);
  readonly thread = httpResource<InboxThread>(() => (this.id() ? `/api/inbox/${encodeURIComponent(this.id()!)}` : undefined));
  readonly t = computed(() => (this.thread.hasValue() ? this.thread.value() : null));
  readonly win = computed(() => windowState(this.t()?.windowUntil ?? null, this.now()));

  #lastCount = 0;

  constructor() {
    // New messages: the list every 10 s, the open chat every 5 s.
    let tick = 0;
    const timer = setInterval(() => {
      this.now.set(Date.now());
      tick++;
      if (this.id()) this.thread.reload();
      if (tick % 2 === 0) this.threads.reload();
    }, 5000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));

    // Opening a chat marks it read; the newest message is kept in view.
    effect(() => {
      const id = this.id();
      if (!id) return;
      untracked(() => {
        this.draft.set('');
        this.error.set(null);
        this.info.set(null);
        this.#lastCount = 0;
        this.#api.post(`/api/inbox/${encodeURIComponent(id)}/read`, {}).then(() => this.threads.reload()).catch(() => {});
      });
    });
    effect(() => {
      const n = this.t()?.messages.length ?? 0;
      if (n !== this.#lastCount) {
        this.#lastCount = n;
        queueMicrotask(() => { const el = this.scroller()?.nativeElement; if (el) el.scrollTop = el.scrollHeight; });
      }
    });
  }

  refusal(code: string | null): string {
    return refusalText(code);
  }

  /** This chat's queued replies not yet in the thread (dropped after a minute: by then it is sent or shown as not sent). */
  pendingFor(t: InboxThread): { text: string; at: number }[] {
    const now = this.now();
    return this.pending().filter((p) => p.id === t.identityId && now - p.at < 60_000
      && !t.messages.some((m) => m.direction === 'out' && m.body === p.text && Date.parse(m.at) >= p.at - 10_000));
  }

  statusOf(o: InboxOrder): string {
    return orderStatus(o);
  }

  tracking(o: InboxOrder): string {
    return trackingText(o);
  }

  fill(body: string, name: string | null): string {
    return fillName(body, name);
  }

  /** Puts text in the reply box: replaces an empty draft, otherwise adds it on a new line. */
  insert(text: string): void {
    const d = this.draft().trimEnd();
    this.draft.set(d ? `${d}\n${text}` : text);
  }

  async saveQuick(e: { id: number | null; title: string; body: string; sort: number }): Promise<void> {
    await this.#run(async () => {
      const body = { title: e.title.trim(), body: e.body.trim(), sort: e.sort };
      if (e.id === null) await this.#api.post('/api/inbox/quick-replies', body);
      else await this.#api.put(`/api/inbox/quick-replies/${e.id}`, body);
      this.editing.set(null);
      this.quick.reload();
    }, 'The saved answer was not saved.');
  }

  async deleteQuick(q: QuickReply): Promise<void> {
    if (!confirm(`Delete the saved answer "${q.title}"?`)) return;
    await this.#run(async () => {
      await this.#api.delete(`/api/inbox/quick-replies/${q.id}`);
      this.quick.reload();
    }, 'The saved answer was not deleted.');
  }

  windowOf(t: InboxThreadRow) {
    return windowState(t.windowUntil, this.now());
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  display(phone: string | null): string {
    if (!phone) return 'Unknown number';
    const d = phone.replace(/\D/g, '');
    return d.length === 12 && d.startsWith('91') ? `+91 ${d.slice(2, 7)} ${d.slice(7)}` : '+' + d;
  }

  when(iso: string): string {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return d.toLocaleString('en-IN', today ? { hour: 'numeric', minute: '2-digit' } : { day: 'numeric', month: 'short' });
  }

  time(iso: string): string {
    return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
  }

  enterToSend(e: Event): void {
    const k = e as KeyboardEvent;
    if (k.shiftKey || k.isComposing) return;
    e.preventDefault();
    if (this.draft().trim() && !this.busy()) void this.reply(null);
  }

  async reply(event: Event | null): Promise<void> {
    event?.preventDefault();
    const text = this.draft().trim();
    if (!text) return;
    const id = this.id()!;
    await this.#run(async () => {
      await this.#api.post(`/api/inbox/${encodeURIComponent(id)}/reply`, { text });
      this.draft.set('');
      this.pending.update((list) => [...list, { id, text, at: Date.now() }]);
      this.#soon();
    }, 'The reply was not sent.');
  }

  async followUp(templateKey: string): Promise<void> {
    if (!confirm('Send the follow-up template to this customer? Meta charges it as a utility message.')) return;
    await this.#run(async () => {
      await this.#api.post(`/api/inbox/${encodeURIComponent(this.id()!)}/template`, { templateKey });
      this.info.set('Follow-up queued. It appears here once WhatsApp accepts it, or with the reason if it cannot be sent.');
      this.#soon();
    }, 'The template was not sent.');
  }

  async setStatus(status: 'open' | 'done'): Promise<void> {
    await this.#run(async () => {
      await this.#api.post(`/api/inbox/${encodeURIComponent(this.id()!)}/status`, { status });
      if (status === 'done' && this.status() === 'open') await this.#router.navigate(['/inbox']);
    }, 'That did not work.');
  }

  /** The worker sends within a second or two: look again shortly. */
  #soon(): void {
    for (const ms of [1500, 4000]) setTimeout(() => { this.now.set(Date.now()); this.thread.reload(); }, ms);
  }

  async #run(work: () => Promise<unknown>, fallback: string): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await work();
      this.thread.reload();
      this.threads.reload();
    } catch (e) {
      this.error.set(problemDetail(e, fallback));
    } finally {
      this.busy.set(false);
    }
  }
}
