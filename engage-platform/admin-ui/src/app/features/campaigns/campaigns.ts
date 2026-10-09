import { httpResource } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TokenService } from '../../core/auth/token.service';
import { InrPipe, IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';
import { Campaign } from './campaign-rules';

/** Every campaign, newest first. */
@Component({
  selector: 'app-campaigns',
  imports: [RouterLink, IstPipe, InrPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-end justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Campaigns</h1>
        <p class="text-sm text-slate-500">Draft, dry run, approval, then a throttled run. Policy still decides every send.</p>
      </div>
      @if (canEdit()) {
        <a routerLink="/campaigns/new" class="btn-primary">New campaign</a>
      }
    </header>
    @if (campaigns.isLoading() && !campaigns.hasValue()) {
      <app-loading />
    } @else if (campaigns.error()) {
      <app-error [error]="campaigns.error()" (retry)="campaigns.reload()" />
    } @else if (campaigns.hasValue()) {
      @if (campaigns.value().length === 0) {
        <app-empty text="No campaigns yet." />
      } @else {
        <table class="table">
          <thead><tr><th>Name</th><th>Channel</th><th>Segment</th><th>Status</th><th class="num">Est. cost</th><th>Created</th></tr></thead>
          <tbody>
            @for (c of campaigns.value(); track c.id) {
              <tr>
                <td><a class="font-medium underline" [routerLink]="['/campaigns', c.id]">{{ c.name }}</a></td>
                <td>{{ c.channel }}{{ c.followUpChannel ? ' → ' + c.followUpChannel : '' }}</td>
                <td>{{ c.segmentName ?? '—' }}</td>
                <td><span class="badge" [class.badge-green]="c.status === 'RUNNING'" [class.badge-red]="c.status === 'PAUSED'">{{ c.status }}</span></td>
                <td class="num">{{ c.estimatedCostPaise | inr }}</td>
                <td>{{ c.createdAt | ist }} · {{ c.createdBy }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class CampaignsPage {
  readonly #tokens = inject(TokenService);
  readonly campaigns = httpResource<Campaign[]>(() => '/api/campaigns');
  readonly canEdit = computed(() => this.#tokens.has('CAMPAIGN_EDIT'));
}
