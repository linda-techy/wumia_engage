import { httpResource } from '@angular/common/http';
import { Component } from '@angular/core';
import { IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

interface WaState {
  language: string;
  providerName: string;
  requestedCategory: string;
  approvedCategory: string | null;
  status: string;
  quality: string;
  rejectedReason: string | null;
  syncedAt: string;
  categoryMismatch: boolean;
}

interface TemplateRow {
  key: string;
  channel: string;
  category: string;
  status: string;
  updatedAt: string;
  whatsapp: WaState[];
  categoryMismatch: boolean;
}

/**
 * Templates (P6-T04): copy lives in code; this shows each one's status and,
 * for WhatsApp, Meta's verdict per language. A template approved in another
 * category than requested is red: a utility template approved as marketing
 * costs about 7x per send.
 */
@Component({
  selector: 'app-templates',
  imports: [IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Templates</h1>
      <p class="text-sm text-slate-500">Copy is written and reviewed in code. WhatsApp templates also need Meta's approval.</p>
    </header>
    @if (templates.isLoading() && !templates.hasValue()) {
      <app-loading />
    } @else if (templates.error()) {
      <app-error [error]="templates.error()" (retry)="templates.reload()" />
    } @else if (templates.hasValue()) {
      @if (templates.value().length === 0) {
        <app-empty text="No templates registered yet." />
      } @else {
        <table class="table">
          <thead><tr><th>Template</th><th>Channel</th><th>Category</th><th>Status</th><th>Meta (WhatsApp)</th></tr></thead>
          <tbody>
            @for (t of templates.value(); track t.key) {
              <tr [class.bg-red-50]="t.categoryMismatch">
                <td class="font-mono text-xs">{{ t.key }}</td>
                <td>{{ t.channel }}</td>
                <td>{{ t.category }}</td>
                <td><span class="badge" [class.badge-green]="t.status === 'active'">{{ t.status }}</span></td>
                <td class="text-xs">
                  @for (w of t.whatsapp; track w.language) {
                    <p>
                      {{ w.language }}: {{ w.status }} · quality <span [class.font-semibold]="w.quality === 'RED'" [class.text-red-700]="w.quality === 'RED'">{{ w.quality }}</span>
                      @if (w.categoryMismatch) {
                        · <strong class="text-red-700">approved as {{ w.approvedCategory }}, requested {{ w.requestedCategory }}</strong>
                      }
                      @if (w.rejectedReason) { · {{ w.rejectedReason }} }
                      <span class="text-slate-500">· synced {{ w.syncedAt | ist }}</span>
                    </p>
                  } @empty {
                    <span class="text-slate-400">—</span>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class TemplatesPage {
  readonly templates = httpResource<TemplateRow[]>(() => '/api/templates');
}
