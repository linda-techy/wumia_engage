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
          <img [src]="image()" alt="Poster" class="mt-3 aspect-[2/1] w-full rounded-2xl object-cover" />
        }
      </div>
      <figcaption class="mt-2 text-xs text-slate-500">
        How Chrome on Android shows it, expanded. iPhone and Safari show the title and text without the poster.
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

  readonly shownTitle = computed(() => cut(this.title(), TITLE_MAX));
  readonly shownBody = computed(() => cut(this.body(), BODY_MAX));
  readonly host = computed(() => site(this.url()));
}
