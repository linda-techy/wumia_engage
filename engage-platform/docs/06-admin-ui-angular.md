# 06 — Admin console (Angular 22)

Angular 22 is signal-first: Signal Forms and the Resource API (`resource`, `rxResource`, `httpResource`) are stable, and `OnPush` is the default change detection strategy. This app is built for that, not retrofitted onto it.

## Stack

| Concern | Choice | Why |
|---|---|---|
| Components | Standalone, zoneless | No NgModules, no Zone.js patching |
| State | Signals + `httpResource` | No NgRx. See below. |
| Forms | Signal Forms | Typed, signal-native, no `FormBuilder` ceremony |
| Data reads | `httpResource` | Declarative fetch, loading/error as signals |
| API client | Generated from OpenAPI | Micronaut emits the spec at compile time |
| Charts | ECharts via `ngx-echarts` | Handles the data density; Chart.js struggles past ~10k points |
| Tables | CDK virtual scroll | Send logs run to millions of rows |
| Styling | Tailwind + CDK a11y primitives | No component library lock-in |
| Testing | Vitest + Playwright | Vitest is the Angular default now |

**No NgRx.** This is a server-state application: almost everything on screen is a cached view of a database row. `httpResource` plus a handful of signals covers it, and a global store would mostly duplicate server state while adding boilerplate and a second source of truth. The only genuinely client-side state is the campaign composer draft, which is one signal in one service.

## Structure

```
admin-ui/src/app/
├─ core/
│  ├─ auth/          token service, interceptor, guards
│  ├─ api/           generated client + typed wrappers
│  └─ layout/        shell, nav, kill-switch header
├─ features/
│  ├─ dashboard/
│  ├─ campaigns/     list, composer, detail, approval queue
│  ├─ journeys/      list, run inspector, per-journey config
│  ├─ templates/     editor with live preview + Meta status
│  ├─ segments/      visual builder
│  ├─ customers/     lookup, 360 view, consent timeline
│  ├─ settings/      generated config forms, operators, API keys
│  └─ audit/         filterable log with diffs
└─ shared/           money/date pipes for en-IN, masked PII, confirm dialogs
```

Routes lazy-load per feature and are role-guarded:

```typescript
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/auth/login.component') },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./core/layout/shell.component'),
    children: [
      { path: 'dashboard', loadComponent: () => import('./features/dashboard/dashboard.component') },
      {
        path: 'campaigns',
        canActivate: [roleGuard('CAMPAIGN_EDIT')],
        loadChildren: () => import('./features/campaigns/routes'),
      },
      {
        path: 'settings',
        canActivate: [roleGuard('CONFIG_ADMIN')],
        loadChildren: () => import('./features/settings/routes'),
      },
    ],
  },
];
```

The guard is a UX affordance, not a security control. The server enforces the same roles on every endpoint; the guard only stops the user navigating to a page that would return 403 anyway.

## Auth in the browser

```typescript
@Injectable({ providedIn: 'root' })
export class TokenService {
  // In memory only. Never localStorage: an admin console renders
  // customer-supplied strings (names, addresses, WhatsApp replies) and one
  // missed escape turns an XSS into token theft. The refresh token lives in an
  // HttpOnly cookie the page cannot read.
  readonly #accessToken = signal<string | null>(null);
  readonly operator = signal<Operator | null>(null);

  readonly isAuthenticated = computed(() => this.#accessToken() !== null);
  readonly roles = computed(() => this.operator()?.roles ?? []);

  has(role: Role): boolean {
    return this.roles().includes(role) || this.roles().includes('OWNER');
  }

  token(): string | null { return this.#accessToken(); }
  set(token: string, operator: Operator) {
    this.#accessToken.set(token);
    this.operator.set(operator);
  }
  clear() { this.#accessToken.set(null); this.operator.set(null); }
}
```

The interceptor refreshes on 401, with a shared in-flight promise so twelve parallel requests trigger one refresh rather than twelve:

```typescript
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const tokens = inject(TokenService);
  const auth = inject(AuthService);

  const withToken = (t: string | null) =>
    t ? req.clone({ setHeaders: { Authorization: `Bearer ${t}` } }) : req;

  return next(withToken(tokens.token())).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status !== 401 || req.url.includes('/auth/')) throw err;
      // refresh() memoises the in-flight call and shares it.
      return auth.refresh().pipe(switchMap((t) => next(withToken(t))));
    }),
  );
};
```

## Reads with `httpResource`

```typescript
@Component({
  selector: 'app-campaign-list',
  template: `
    @if (campaigns.isLoading()) {
      <app-skeleton-table rows="8" />
    } @else if (campaigns.error()) {
      <app-error-state [error]="campaigns.error()" (retry)="campaigns.reload()" />
    } @else {
      <app-data-table [rows]="campaigns.value()" [columns]="columns" />
    }
  `,
})
export class CampaignListComponent {
  readonly status = signal<CampaignStatus | 'ALL'>('ALL');
  readonly page = signal(0);

  // Re-fetches automatically when either signal changes. No subscriptions,
  // no manual teardown, no race between an old response and a new filter.
  readonly campaigns = httpResource<Campaign[]>(() => ({
    url: '/api/campaigns',
    params: { status: this.status(), page: this.page(), size: 50 },
  }));
}
```

## The composer is the important screen

Four steps, and the send button stays disabled until the dry run has produced numbers a human has actually looked at.

```
1. Audience   → pick or build a segment, live size, sample of 10 masked profiles
2. Message    → template picker, variable mapping, live phone-frame preview
3. Schedule   → now / scheduled / throttle rate / budget cap / holdout %
4. Review     → dry-run breakdown, spend estimate, typed confirmation
```

```typescript
@Component({
  selector: 'app-campaign-review',
  template: `
    <app-stat-row>
      <app-stat label="Audience"      [value]="estimate.value()?.audienceSize | number:'':'en-IN'" />
      <app-stat label="Will receive"  [value]="estimate.value()?.deliverable | number:'':'en-IN'" />
      <app-stat label="Est. spend"    [value]="estimate.value()?.costPaise | paise" emphasis />
    </app-stat-row>

    <!-- The block breakdown is the most useful thing on this screen. A large
         'no consent' bucket means acquisition is broken; a large 'frequency
         cap' bucket means you are already over-messaging these people. -->
    <app-block-breakdown [blocks]="estimate.value()?.blocks ?? {}" />

    @if (stale()) {
      <app-banner tone="warn">
        Policy config changed since this estimate. Re-run before sending.
        <button (click)="estimate.reload()">Re-estimate</button>
      </app-banner>
    }

    @if (needsApproval()) {
      <app-banner tone="info">
        Estimated spend exceeds {{ threshold() | paise }}. A second operator
        must approve before this can send.
      </app-banner>
    }

    <app-typed-confirm
      [phrase]="'SEND ' + (estimate.value()?.deliverable | number:'':'en-IN')"
      [disabled]="!canSend()"
      (confirmed)="send()" />
  `,
})
export class CampaignReviewComponent {
  readonly campaignId = input.required<string>();
  readonly estimate = httpResource<Estimate>(() =>
    ({ url: `/api/campaigns/${this.campaignId()}/estimate` }));

  readonly stale = computed(() =>
    this.estimate.value()?.configSnapshotId !== this.config.currentSnapshotId());

  readonly canSend = computed(() =>
    !!this.estimate.value() && !this.stale() && this.tokens.has('CAMPAIGN_SEND'));
}
```

The typed confirmation makes the operator type `SEND 31,884`. A checkbox gets clicked reflexively; typing the actual recipient count forces one conscious look at how many people this reaches. Cheap control, disproportionate effect.

## Settings are generated, not hand-written

Because `config_keys` carries `valueType` and `jsonSchema`, the settings screen renders from metadata. Adding a setting is a backend migration with no frontend change.

```typescript
@Component({
  selector: 'app-config-field',
  template: `
    <label [for]="key().key">
      {{ key().label }}
      @if (key().risk === 'CRITICAL') { <app-risk-badge /> }
    </label>
    <p class="help">{{ key().helpText }}</p>

    @switch (key().valueType) {
      @case ('INT')        { <input type="number" [control]="field" /> }
      @case ('BOOL')       { <app-toggle [control]="field" /> }
      @case ('TIME_RANGE') { <app-time-range [control]="field" /> }
      @case ('ENUM')       { <app-select [control]="field" [options]="key().options" /> }
      @default             { <app-json-editor [control]="field" [schema]="key().jsonSchema" /> }
    }

    <!-- Provenance beside every value. "Who set this and why" is the question
         you ask during an incident, and it should not require the audit page. -->
    <app-provenance
      [changedBy]="key().current.changedBy"
      [since]="key().current.since"
      [reason]="key().current.reason" />

    @if (key().pending) {
      <app-banner tone="warn">
        Change to {{ key().pending.value }} proposed by
        {{ key().pending.changedBy }} — awaiting a second approver.
      </app-banner>
    }
  `,
})
export class ConfigFieldComponent {
  readonly key = input.required<ConfigKeyView>();
}
```

`CRITICAL` fields open a diff dialog requiring a typed reason before saving. The reason is mandatory at the API layer too, so it cannot be skipped by calling the endpoint directly.

## Dashboard

Above the fold, in this order:

1. **Kill switches** — halt channel, halt journey, halt all marketing. Always visible, in the header, on every page.
2. **Spend today** vs budget, per channel, per category. A filling bar, not a number in a table.
3. **Quality rating** per WhatsApp number, with a 30-day trend. Treated as an incident metric — a High→Medium drop pages someone.
4. **Block reasons, 24h** — the fastest signal that something is misconfigured.
5. **Journey health** — runs entered, completed, failed, stalled.
6. **Opt-outs, 24h** with a trend. Rising opt-outs precede a quality-rating drop, usually by a few days.

Charts poll every 30s via `httpResource` with a signal-driven `refreshTrigger`, not WebSockets. This is an operations dashboard with a handful of viewers; a socket layer would be infrastructure to maintain for no visible benefit.

## Performance

**Virtual scrolling everywhere lists get long.** The send log is millions of rows; a naive table locks the tab.

```typescript
@Component({
  template: `
    <cdk-virtual-scroll-viewport itemSize="52" class="h-[70vh]">
      @for (row of rows(); track row.id) {
        <app-send-row [send]="row" />
      }
    </cdk-virtual-scroll-viewport>
  `,
})
```

**Server-side pagination, filtering and sorting.** No endpoint returns an unbounded collection; every list is cursor-paginated with a hard cap.

**`OnPush` is the default in Angular 22**, so components re-render on signal change rather than on every event. Keep computed values in `computed()` rather than calling methods from templates.

## Accessibility and locale

Currency is formatted `en-IN` throughout, which means lakh/crore grouping: `₹27,506` and `₹1,72,000`, not `₹172,000`. Getting this wrong makes every number on the screen read as foreign to the people using it.

Dates render in IST with the timezone shown, because the entire policy layer reasons in IST and an ambiguous timestamp during an incident is a real hazard.

Keyboard navigation and focus management on every dialog via the CDK a11y primitives. Colour is never the only carrier of meaning in the charts — the dataviz guidance applies here as much as anywhere.

## Sources

- [Angular 22: The Most Important New Features at a Glance](https://www.angulararchitects.io/en/blog/angular-22-the-most-important-new-features-at-a-glance/)
- [Angular version history and support windows](https://endoflife.date/angular)
