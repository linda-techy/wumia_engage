import { httpResource } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TokenService } from '../../core/auth/token.service';
import { IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

export interface SegmentRow {
  id: string;
  name: string;
  description: string | null;
  definition: Record<string, unknown>;
  lastSize: number | null;
  lastSizedAt: string | null;
  createdBy: string | null;
  updatedAt: string;
}

/** Saved audiences. Sizes are counts only; nobody's identity is shown here. */
@Component({
  selector: 'app-segments',
  imports: [RouterLink, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-end justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Segments</h1>
        <p class="text-sm text-slate-500">Audiences built from orders, carts, consent and reachability.</p>
      </div>
      @if (canEdit()) {
        <a routerLink="/segments/new" class="btn-primary">New segment</a>
      }
    </header>
    @if (segments.isLoading() && !segments.hasValue()) {
      <app-loading />
    } @else if (segments.error()) {
      <app-error [error]="segments.error()" (retry)="segments.reload()" />
    } @else if (segments.hasValue()) {
      @if (segments.value().length === 0) {
        <app-empty text="No segments yet." />
      } @else {
        <table class="table">
          <thead><tr><th>Name</th><th class="num">Size</th><th>Counted</th><th>Created by</th></tr></thead>
          <tbody>
            @for (s of segments.value(); track s.id) {
              <tr>
                <td><a class="font-medium underline" [routerLink]="['/segments', s.id]">{{ s.name }}</a>
                  @if (s.description) { <p class="text-xs text-slate-500">{{ s.description }}</p> }</td>
                <td class="num">{{ s.lastSize ?? '—' }}</td>
                <td>{{ s.lastSizedAt | ist }}</td>
                <td>{{ s.createdBy ?? '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class SegmentsPage {
  readonly #tokens = inject(TokenService);
  readonly segments = httpResource<SegmentRow[]>(() => '/api/segments');
  readonly canEdit = computed(() => this.#tokens.has('CAMPAIGN_EDIT'));
}
