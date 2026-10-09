import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TokenService } from '../../core/auth/token.service';
import { Api } from '../../shared/api';
import { problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { Builder, Condition, Definition, Field, fromDefinition, OP_LABELS, toDefinition } from './segment-model';
import { SegmentRow } from './segments';

/**
 * Segment builder with a live count. The rows compile to the server's JSON
 * DSL; the server compiles that to SQL. A definition deeper than one level
 * of all/any is edited as JSON. Unavailable fields are listed with the
 * reason, not offered.
 */
@Component({
  selector: 'app-segment-builder',
  imports: [RouterLink, LoadingState, ErrorState],
  template: `
    <a routerLink="/segments" class="text-sm text-slate-600 underline">← Segments</a>
    <header class="mb-6 mt-3">
      <h1 class="text-2xl font-semibold">{{ id() ? 'Edit segment' : 'New segment' }}</h1>
    </header>

    @if (fields.isLoading() || (id() && existing.isLoading())) {
      <app-loading />
    } @else if (fields.error() || existing.error()) {
      <app-error [error]="fields.error() ?? existing.error()" [retryable]="false" />
    } @else {
      <div class="grid gap-6 lg:grid-cols-[1fr_16rem]">
        <form class="space-y-5" (submit)="save($event)">
          <div class="grid grid-cols-2 gap-4">
            <label class="text-sm"><span class="mb-1 block font-medium">Name</span>
              <input class="input" [value]="name()" (input)="name.set(val($event))" [disabled]="!canEdit()" /></label>
            <label class="text-sm"><span class="mb-1 block font-medium">Description</span>
              <input class="input" [value]="description()" (input)="description.set(val($event))" [disabled]="!canEdit()" /></label>
          </div>

          @if (!advanced()) {
            <fieldset class="card space-y-3" [disabled]="!canEdit()">
              <legend class="px-1 text-sm font-medium">People who match
                <select class="input ml-1 inline-block w-auto py-1" [value]="builder().match" (change)="setMatch(val($event))">
                  <option value="all">all</option>
                  <option value="any">any</option>
                </select>
                of these
              </legend>
              @for (c of builder().conditions; track $index; let i = $index) {
                <div class="flex flex-wrap items-center gap-2">
                  <label class="text-xs"><input type="checkbox" [checked]="c.not" (change)="update(i, { not: checked($event) })" /> not</label>
                  <select class="input w-56" [value]="c.field" (change)="setField(i, val($event))" aria-label="Field">
                    <option value="" disabled>Pick a field</option>
                    @for (f of available(); track f.name) { <option [value]="f.name">{{ f.label }}</option> }
                  </select>
                  @if (field(c.field); as f) {
                    <select class="input w-48" [value]="c.op" (change)="update(i, { op: val($event) })" aria-label="Operator">
                      @for (o of f.ops; track o) { <option [value]="o">{{ opLabel(o) }}</option> }
                    </select>
                    @if (f.valueKind === 'boolean') {
                      <select class="input w-28" [value]="c.value" (change)="update(i, { value: val($event) })" aria-label="Value">
                        <option value="true">yes</option><option value="false">no</option>
                      </select>
                    } @else if (f.valueKind.startsWith('enum:')) {
                      <select class="input w-40" [value]="c.value" (change)="update(i, { value: val($event) })" aria-label="Value">
                        @for (o of options(f); track o) { <option [value]="o">{{ o }}</option> }
                      </select>
                    } @else {
                      <input class="input w-64" [value]="c.value" (input)="update(i, { value: val($event) })" aria-label="Value"
                             [placeholder]="hint(f.valueKind)" />
                    }
                  }
                  <button type="button" class="text-sm text-slate-500 underline" (click)="remove(i)" aria-label="Remove condition">remove</button>
                </div>
              }
              <button type="button" class="btn py-1" (click)="add()">Add condition</button>
            </fieldset>
          } @else {
            <label class="block text-sm"><span class="mb-1 block font-medium">Definition (JSON)</span>
              <textarea rows="10" class="input font-mono text-xs" [value]="json()" (input)="json.set(val($event))" [disabled]="!canEdit()"></textarea></label>
          }
          <button type="button" class="block text-sm underline" (click)="toggleAdvanced()">{{ advanced() ? 'Use the builder' : 'Edit as JSON' }}</button>

          @if (definitionError()) {
            <p class="text-sm text-amber-800">{{ definitionError() }}</p>
          }
          @if (error()) {
            <p class="rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p>
          }
          @if (canEdit()) {
            <button type="submit" class="btn-primary" [disabled]="busy() || !definition() || !name().trim()">Save segment</button>
          }
        </form>

        <aside class="space-y-4">
          <div class="card">
            <p class="label">Matching people</p>
            <p class="stat">{{ counting() ? '…' : (size() ?? '—') }}</p>
            @if (countError()) { <p class="mt-1 text-xs text-red-700">{{ countError() }}</p> }
            @if (!canEdit()) { <p class="mt-1 text-xs text-slate-500">Live counts need campaign editing rights.</p> }
          </div>
          @if (unavailable().length) {
            <div class="card text-xs text-slate-600">
              <p class="mb-2 font-medium">Not available yet</p>
              @for (f of unavailable(); track f.name) { <p class="mb-1"><strong>{{ f.label }}</strong>: {{ f.note }}</p> }
            </div>
          }
        </aside>
      </div>
    }
  `,
})
export default class SegmentBuilderPage {
  readonly #api = inject(Api);
  readonly #tokens = inject(TokenService);
  readonly #router = inject(Router);

  /** Route parameter; absent on /segments/new. */
  readonly id = input<string>();

  readonly fields = httpResource<Field[]>(() => '/api/segments/fields');
  readonly existing = httpResource<SegmentRow>(() => (this.id() ? `/api/segments/${encodeURIComponent(this.id()!)}` : undefined));
  readonly available = computed(() => (this.fields.hasValue() ? this.fields.value().filter((f) => f.available) : []));
  readonly unavailable = computed(() => (this.fields.hasValue() ? this.fields.value().filter((f) => !f.available) : []));
  readonly canEdit = computed(() => this.#tokens.has('CAMPAIGN_EDIT'));

  readonly name = signal('');
  readonly description = signal('');
  readonly builder = signal<Builder>({ match: 'all', conditions: [] });
  readonly advanced = signal(false);
  readonly json = signal('{}');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly size = signal<number | null>(null);
  readonly counting = signal(false);
  readonly countError = signal<string | null>(null);

  readonly #compiled = computed(() => {
    if (this.advanced()) {
      try {
        return { ok: true as const, definition: JSON.parse(this.json()) as Definition };
      } catch {
        return { ok: false as const, error: 'The JSON is not valid yet.' };
      }
    }
    return toDefinition(this.builder(), this.available());
  });
  readonly definition = computed(() => (this.#compiled().ok ? (this.#compiled() as { definition: Definition }).definition : null));
  readonly definitionError = computed(() => {
    const c = this.#compiled();
    return c.ok ? null : c.error;
  });

  #loaded = false;
  #timer: ReturnType<typeof setTimeout> | undefined;
  #seq = 0;

  constructor() {
    effect(() => {
      if (this.#loaded || !this.existing.hasValue()) return;
      const s = this.existing.value();
      this.#loaded = true;
      untracked(() => {
        this.name.set(s.name);
        this.description.set(s.description ?? '');
        const b = fromDefinition(s.definition);
        if (b) this.builder.set(b);
        else {
          this.advanced.set(true);
          this.json.set(JSON.stringify(s.definition, null, 2));
        }
      });
    });
    // Live count, debounced: the latest definition only.
    effect(() => {
      const d = this.definition();
      if (!this.canEdit()) return;
      clearTimeout(this.#timer);
      if (!d) {
        untracked(() => this.size.set(null));
        return;
      }
      this.#timer = setTimeout(() => void this.#count(d), 600);
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.#timer));
  }

  field(name: string): Field | undefined {
    return this.available().find((f) => f.name === name);
  }

  opLabel(op: string): string {
    return OP_LABELS[op] ?? op;
  }

  options(f: Field): string[] {
    return f.valueKind.slice(5).split(',');
  }

  hint(kind: string): string {
    return kind === 'period' ? 'days, e.g. 60' : kind === 'rupees' ? '₹ amount' : kind === 'integer' ? 'a number' : 'comma-separated, e.g. KL, TN';
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  setMatch(m: string): void {
    this.builder.update((b) => ({ ...b, match: m === 'any' ? 'any' : 'all' }));
  }

  add(): void {
    this.builder.update((b) => ({ ...b, conditions: [...b.conditions, { field: '', op: '', value: '', not: false }] }));
  }

  remove(i: number): void {
    this.builder.update((b) => ({ ...b, conditions: b.conditions.filter((_, j) => j !== i) }));
  }

  update(i: number, patch: Partial<Condition>): void {
    this.builder.update((b) => ({ ...b, conditions: b.conditions.map((c, j) => (j === i ? { ...c, ...patch } : c)) }));
  }

  setField(i: number, name: string): void {
    const f = this.field(name);
    const value = f?.valueKind === 'boolean' ? 'true' : f?.valueKind.startsWith('enum:') ? this.options(f)[0] : '';
    this.update(i, { field: name, op: f?.ops[0] ?? '', value });
  }

  toggleAdvanced(): void {
    if (!this.advanced()) {
      this.json.set(JSON.stringify(this.definition() ?? {}, null, 2));
      this.advanced.set(true);
      return;
    }
    const b = fromDefinition(this.definition());
    if (b) {
      this.builder.set(b);
      this.advanced.set(false);
    } else {
      this.error.set('This definition nests deeper than the builder shows; keep editing it as JSON.');
    }
  }

  async #count(definition: Definition): Promise<void> {
    const seq = ++this.#seq;
    this.counting.set(true);
    this.countError.set(null);
    try {
      const r = await this.#api.post<{ size: number }>('/api/segments/preview', { definition });
      if (seq === this.#seq) this.size.set(r.size);
    } catch (e) {
      if (seq === this.#seq) {
        this.size.set(null);
        this.countError.set(problemDetail(e, 'Could not count.'));
      }
    } finally {
      if (seq === this.#seq) this.counting.set(false);
    }
  }

  async save(event: Event): Promise<void> {
    event.preventDefault();
    const definition = this.definition();
    if (!definition) return;
    this.busy.set(true);
    this.error.set(null);
    const body = { name: this.name().trim(), description: this.description().trim() || null, definition };
    try {
      const saved = this.id()
        ? await this.#api.put<SegmentRow>(`/api/segments/${encodeURIComponent(this.id()!)}`, body)
        : await this.#api.post<SegmentRow>('/api/segments', body);
      await this.#router.navigate(['/segments', saved.id]);
      this.size.set(saved.lastSize);
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not save the segment.'));
    } finally {
      this.busy.set(false);
    }
  }
}
