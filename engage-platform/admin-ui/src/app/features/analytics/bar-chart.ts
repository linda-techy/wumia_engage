import { Component, computed, input } from '@angular/core';
import { Bar } from './analytics-model';

/**
 * A plain bar chart in SVG: one bar per day, the largest value at the top,
 * a label under the first, middle and last bars. No chart library needed for
 * one series; screen readers get the numbers as a table instead.
 */
@Component({
  selector: 'app-bar-chart',
  template: `
    <figure>
      <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" class="h-48 w-full" role="img" [attr.aria-label]="label()">
        <line x1="0" [attr.y1]="height - 18" [attr.x2]="width" [attr.y2]="height - 18" class="stroke-slate-300" />
        @for (b of geometry(); track b.label) {
          <rect [attr.x]="b.x" [attr.y]="b.y" [attr.width]="b.w" [attr.height]="b.h" class="fill-emerald-500">
            <title>{{ b.label }}: {{ b.text }}</title>
          </rect>
          @if (b.showLabel) {
            <text [attr.x]="b.x + b.w / 2" [attr.y]="height - 4" text-anchor="middle" class="fill-slate-500 text-[10px]">{{ b.short }}</text>
          }
        }
        <text x="0" y="10" class="fill-slate-500 text-[10px]">{{ maxText() }}</text>
      </svg>
      <table class="sr-only">
        <caption>{{ label() }}</caption>
        @for (b of bars(); track b.label) { <tr><th>{{ b.label }}</th><td>{{ format()(b.value) }}</td></tr> }
      </table>
    </figure>
  `,
})
export class BarChart {
  readonly bars = input.required<Bar[]>();
  readonly label = input('Daily values');
  readonly format = input<(v: number) => string>((v) => String(v));

  readonly width = 600;
  readonly height = 180;

  readonly max = computed(() => Math.max(1, ...this.bars().map((b) => b.value)));
  readonly maxText = computed(() => this.format()(this.max()));

  readonly geometry = computed(() => {
    const bars = this.bars();
    const n = Math.max(1, bars.length);
    const slot = this.width / n;
    const w = Math.max(1, slot * 0.7);
    const top = 16;
    const bottom = this.height - 18;
    const mid = Math.floor((n - 1) / 2);
    return bars.map((b, i) => {
      const h = ((bottom - top) * b.value) / this.max();
      return {
        label: b.label,
        text: this.format()(b.value),
        short: b.label.slice(5),
        x: i * slot + (slot - w) / 2,
        y: bottom - h,
        w,
        h,
        showLabel: i === 0 || i === n - 1 || i === mid,
      };
    });
  });
}
