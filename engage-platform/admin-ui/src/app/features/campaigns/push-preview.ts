import { Component, computed, input } from '@angular/core';

/** The push campaign template whose title and text are written in the composer. */
export const CUSTOM_PUSH_TEMPLATE = 'push_campaign_custom_v1';
export const TITLE_MAX = 40;
export const BODY_MAX = 90;

/** Characters as a person counts them: an emoji is one, not two. */
export function length(text: string): number {
  return [...text].length;
}

/** What the send will show: cut with an ellipsis past the limit, as the server does. */
export function cut(text: string, max: number): string {
  const chars = [...text.trim()];
  return chars.length <= max ? chars.join('') : chars.slice(0, max - 1).join('').trimEnd() + '…';
}

/** "3 days", "1 day 5h", "5h 12m", "45 min": the server's {time_left}, for the preview. */
export function timeLeftText(ms: number): string {
  const minutes = Math.max(1, Math.ceil(ms / 60000));
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  const mins = minutes % 60;
  if (days >= 2) return `${days} days`;
  if (days === 1) return hours === 0 ? '1 day' : `1 day ${hours}h`;
  if (hours > 0) return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
  return `${mins} min`;
}

/** The poster band's digits: HH:MM:SS, or DD:HH:MM from 100 hours, as the server draws them. */
export function countdownDigits(ms: number): { groups: string[]; units: string[] } {
  const s = Math.max(0, Math.floor(ms / 1000));
  const two = (n: number) => String(n).padStart(2, '0');
  if (s >= 100 * 3600) {
    return { groups: [two(Math.min(99, Math.floor(s / 86400))), two(Math.floor((s % 86400) / 3600)), two(Math.floor((s % 3600) / 60))],
             units: ['DAYS', 'HRS', 'MIN'] };
  }
  return { groups: [two(Math.floor(s / 3600)), two(Math.floor((s % 3600) / 60)), two(s % 60)], units: ['HRS', 'MIN', 'SEC'] };
}

/** The site a web push says it is from: the click-through link's host. */
export function site(url: string | null): string {
  try {
    return url ? new URL(url).host.replace(/^www\./, '') : 'your store';
  } catch {
    return 'your store';
  }
}

/**
 * An Android notification, expanded, as Chrome shows a web push: the brand
 * logo, "Chrome · site", the title, the text and the poster underneath. iPhone
 * and Safari show the text only, which the caption says.
 */
@Component({
  selector: 'app-push-preview',
  template: `
    <figure class="w-96 max-w-full">
      <div class="rounded-3xl bg-[#2b2d31] p-4 text-[#e3e3e3] shadow-lg">
        <div class="flex items-center gap-2 text-xs text-[#c4c7c5]">
          @if (icon()) {
            <img [src]="icon()" alt="" class="h-7 w-7 rounded-full object-cover" />
          } @else {
            <span class="flex h-7 w-7 items-center justify-center rounded-full bg-[#cc0101] text-sm font-bold text-white">W</span>
          }
          <span>Chrome · {{ host() }} · now</span>
        </div>
        <p class="mt-2 font-semibold leading-snug text-white">{{ shownTitle() || 'Title' }}</p>
        <p class="mt-1 text-sm leading-snug">{{ shownBody() || 'Text' }}</p>
        @if (image()) {
          <div class="relative mt-3">
            <img [src]="image()" alt="Poster" class="aspect-[2/1] w-full rounded-2xl object-cover" />
            @if (digits(); as d) {
              <div class="absolute inset-x-0 bottom-0 rounded-b-2xl border-t-2 border-[#f5c451] bg-[#14080e]/70 px-2 pb-1.5 pt-1 text-center text-[#f5c451]">
                <p class="text-[9px] font-bold tracking-[0.2em] text-white">OFFER ENDS IN</p>
                <p class="font-mono text-lg font-bold leading-tight">{{ d.groups.join(' : ') }}</p>
                <p class="text-[8px] font-bold tracking-widest text-white">{{ d.units.join(' · ') }}</p>
              </div>
            }
          </div>
        }
      </div>
      <figcaption class="mt-2 text-xs text-slate-500">
        How Chrome on Android shows it, expanded. iPhone and Safari show the title and text without the poster.
        @if (countdownMs() !== null) { The time left is filled in when each push goes; the poster band shows it when the phone opens it. }
      </figcaption>
    </figure>
  `,
})
export class PushPreview {
  readonly title = input('');
  readonly body = input('');
  readonly image = input<string | null>(null);
  readonly url = input<string | null>(null);
  readonly icon = input<string | null>(null);
  /** Milliseconds to the offer's end, for "{time_left}" and the poster band; null without a countdown. */
  readonly countdownMs = input<number | null>(null);
  /** Draw the band on the poster (the campaign's countdown poster is on). */
  readonly countdownPoster = input(false);

  readonly digits = computed(() => (this.countdownPoster() && this.countdownMs() !== null ? countdownDigits(this.countdownMs()!) : null));
  #filled(text: string): string {
    const ms = this.countdownMs();
    return ms === null ? text : text.replaceAll('{time_left}', timeLeftText(ms));
  }

  readonly shownTitle = computed(() => cut(this.#filled(this.title()), TITLE_MAX));
  readonly shownBody = computed(() => cut(this.#filled(this.body()), BODY_MAX));
  readonly host = computed(() => site(this.url()));
}
