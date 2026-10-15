import { HttpClient, HttpHeaders, httpResource } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { problemDetail } from '../../shared/format';

/** POST /api/media, GET /api/media (V38). */
export interface Media {
  id: string;
  url: string;
  contentType: string;
  width: number;
  height: number;
  sizeBytes: number;
  name: string | null;
  wide: boolean;
  createdAt: string;
}

export const MAX_POSTER_BYTES = 1_048_576;

/** Checked in the browser first, so a wrong file fails at once; the server checks again. */
export function posterProblem(file: { type: string; size: number }): string | null {
  if (file.type !== 'image/jpeg' && file.type !== 'image/png') return 'Use a JPEG or PNG image.';
  if (file.size > MAX_POSTER_BYTES) return `The image is ${Math.round(file.size / 1024)} KB; at most 1024 KB.`;
  return null;
}

/**
 * The poster under a push notification: upload one, reuse a recent one, or
 * paste an https link. Shown at 2:1, the shape Chrome crops it to.
 */
@Component({
  selector: 'app-poster-picker',
  template: `
    <section class="card space-y-3" aria-labelledby="poster-h">
      <div>
        <h2 id="poster-h" class="text-sm font-semibold">Poster image</h2>
        <p class="text-xs text-slate-500">
          The large picture under the notification. 1024 × 512 px (2:1), JPEG or PNG, under 1 MB. Shown on Chrome and Edge
          (Android, Windows); iPhone and Safari show the text only. Keep words out of the edges: they get cropped.
        </p>
      </div>

      @if (value(); as url) {
        <div class="w-80 overflow-hidden rounded border border-slate-200 bg-white shadow-sm">
          <div class="px-3 pt-2 text-xs text-slate-500">Preview</div>
          <div class="aspect-[2/1] w-full bg-slate-100">
            <img [src]="url" alt="Poster preview" class="h-full w-full object-cover" (error)="broken.set(true)" (load)="broken.set(false)" />
          </div>
        </div>
        @if (broken()) { <p class="text-xs text-red-800" role="alert">This image does not load. Check the link.</p> }
        <button type="button" class="text-sm underline" (click)="changed.emit(null)">Remove poster</button>
      }

      <div class="flex flex-wrap items-center gap-3">
        <label class="btn cursor-pointer py-1">
          {{ busy() ? 'Uploading…' : 'Upload image' }}
          <input type="file" accept="image/jpeg,image/png" class="sr-only" (change)="upload($event)" [disabled]="busy()" />
        </label>
        <input class="input flex-1" placeholder="or paste an https:// image link" [value]="value() ?? ''"
               (change)="paste($event)" aria-label="Poster image link" />
      </div>
      @if (error()) { <p class="text-sm text-red-800" role="alert">{{ error() }}</p> }
      @if (note()) { <p class="text-xs text-amber-800">{{ note() }}</p> }

      @if ((recent.value() ?? []).length > 0) {
        <div>
          <p class="mb-1 text-xs text-slate-500">Recent posters</p>
          <div class="flex flex-wrap gap-2">
            @for (m of recent.value(); track m.id) {
              <button type="button" class="w-32 overflow-hidden rounded border" [class.ring-2]="m.url === value()"
                      (click)="changed.emit(m.url)" [title]="m.name ?? m.width + ' × ' + m.height">
                <img [src]="m.url" [alt]="m.name ?? 'Poster'" class="aspect-[2/1] w-full object-cover" />
              </button>
            }
          </div>
        </div>
      }
    </section>
  `,
})
export class PosterPicker {
  readonly #http = inject(HttpClient);

  readonly value = input<string | null>(null);
  readonly changed = output<string | null>();

  readonly recent = httpResource<Media[]>(() => '/api/media');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly note = signal<string | null>(null);
  readonly broken = signal(false);

  async upload(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.error.set(null);
    this.note.set(null);
    const problem = posterProblem(file);
    if (problem) {
      this.error.set(problem);
      return;
    }
    this.busy.set(true);
    try {
      const m = await firstValueFrom(this.#http.post<Media>('/api/media', await file.arrayBuffer(), {
        headers: new HttpHeaders({ 'Content-Type': file.type, 'X-File-Name': file.name.slice(0, 120) }),
      }));
      if (!m.wide) this.note.set(`This image is ${m.width} × ${m.height}. Chrome crops to 2:1, so parts may be cut off.`);
      this.changed.emit(m.url);
      this.recent.reload();
    } catch (err) {
      this.error.set(problemDetail(err, 'Could not upload the image.'));
    } finally {
      this.busy.set(false);
    }
  }

  paste(e: Event): void {
    const v = (e.target as HTMLInputElement).value.trim();
    this.error.set(null);
    if (!v) {
      this.changed.emit(null);
    } else if (!v.startsWith('https://')) {
      this.error.set('The link must start with https://');
    } else {
      this.changed.emit(v);
    }
  }
}
