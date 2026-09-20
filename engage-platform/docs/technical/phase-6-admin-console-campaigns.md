# Phase 6 — Admin console and campaigns

**Weeks 9–11 · Owner: frontend + backend · Depends on: Phase 5**

## Goal

Give operators a safe way to see, configure and run the system: login with MFA, role-based access, versioned config, a consent-copy registry, template status, dashboards, kill switches, and manual campaigns with dry run and four-eyes approval.

Most of this is already specified. This phase builds it and adds the Shopify- and FCM-specific pieces.

| Area | Specified in |
|---|---|
| Versioned config, risk tiers, kill switches | [`02-config-and-settings.md`](../02-config-and-settings.md) |
| Login, MFA, token rotation, roles, four-eyes | [`03-auth-and-rbac.md`](../03-auth-and-rbac.md) |
| Campaign lifecycle, dry run, throttled execution | [`05-campaigns.md`](../05-campaigns.md) |
| Angular 22 structure, composer, generated settings | [`06-admin-ui-angular.md`](../06-admin-ui-angular.md) |
| Endpoints and payloads | [`07-api-contract.md`](../07-api-contract.md) |
| Schema | [`db/migration/V2__admin_and_config.sql`](../../db/migration/V2__admin_and_config.sql) |

---

## 1. Build order inside the phase

1. **Auth + RBAC + audit.** Nothing else ships without it.
2. **Kill switches + dashboard.** The first screen an operator needs during an incident.
3. **Config + consent copy registry + templates.** Read-mostly, low risk.
4. **Segments.**
5. **Campaigns.** Last, because it is the most dangerous feature and depends on everything above.

---

## 2. Screens added for this build

### Consent copy registry
Lists every `consent_copy_versions` row: channel, surface, verbatim text, purposes, and the number of grants made under it. Registering a version needs `CONFIG_ADMIN` and is immutable. The storefront rejects a registration whose `copyVersion` is not in this registry (Phase 2 §3). That is what keeps theme-editor copy changes from breaking the evidence trail.

### Push subscriber health
- Active, stale and inactive tokens by browser, with the 30-day trend
- Prompt funnel by surface: soft-shown → soft-accepted → native-granted → token (Phase 2 §11)
- Token churn by reason (rotated, unregistered, dormant)
- iOS/in-app-browser visitors routed to the WhatsApp opt-in

### WhatsApp health
- Number quality and messaging tier, with history
- Templates: requested vs **approved** category (mismatches in red), status, quality
- Capability distribution: `CAPABLE` / `UNKNOWN` / `INCAPABLE`, and the weekly trend of the `UNKNOWN` share
- Opt-in sources: cart, Thank you page, in-thread, and grants per day for each

### Journey inspector
Pick an identity (by exact phone lookup, masked) and see every cascade run, each step, and every policy decision with its reason. This is the screen that answers "why did / didn't this customer get a message" during a complaint. It reads straight from `cascade_attempts` and `sends`, which is why blocked attempts were persisted from Phase 3 onward.

---

## 3. Segments on Shopify data

Segments are a JSON DSL compiled to SQL on the server. The browser never sends SQL. Shopify-derived fields available as predicates:

| Predicate | Source |
|---|---|
| `orders_count`, `last_order_at`, `aov_band`, `net_margin_band` | orders webhooks + nightly profile job |
| `bought_collection`, `bought_product_type`, `bought_size` | order line items |
| `shopify_tag` | `customers/update` tags |
| `city_tier`, `state` | shipping address; drives Hinglish vs English |
| `has_open_cart`, `cart_value` | `carts` |
| `waitlisted_variant` | `stock_waitlist` |
| `push_reachable`, `wa_capable`, `wa_marketing`, `email_marketing` | devices, capability, consent |

```json
{ "all": [
    { "field": "bought_collection", "op": "in", "value": ["kurtas", "ethnic-sets"] },
    { "field": "last_order_at", "op": "older_than", "value": "P60D" },
    { "field": "state", "op": "in", "value": ["KL", "TN", "KA"] }
]}
```

That segment is "ethnicwear buyers in the south who haven't bought in 60 days". It is the audience for an Onam or Pongal campaign, and it is built from data the store already has.

---

## 4. Campaigns per channel

The lifecycle (draft → estimate → approve → arm → throttled run) is in `05-campaigns.md`. Channel-specific rules:

**Push campaigns.**
- The audience is identities with an active, **non-stale** token and a push marketing grant. Stale tokens (no refresh in 30 days) are excluded. They inflate the audience size and deflate the click rate.
- Default throttle 5,000 identities/minute, within the FCM project quota. Back-in-stock and journey pushes keep priority on the shared quota.
- TTL defaults to 12 hours and is capped at 48. A sale push that arrives after the sale has ended does damage.

**WhatsApp campaigns.**
- Marketing templates only, `APPROVED`, quality not RED. The composer does not offer anything else.
- The audience needs `wa_capable = CAPABLE` (never `UNKNOWN`: campaigns do not pay to discover capability) and a WhatsApp marketing grant.
- Default throttle 600/minute (`05-campaigns.md` §Rate limiting). The dry run shows Meta's per-user marketing limit as its own block bucket once 131049 responses start arriving, so operators can see Meta suppression rather than guessing at it.

**Email campaigns.** Phase 7.

**Multi-channel campaigns** go through the orchestrator as a one-off cascade: push first, WhatsApp 6 hours later to those who did not click. This is usually the right shape for a festive launch (`INDIA-PLAYBOOK.md` §4): the free channel carries the volume and the paid channel follows up only where the free one didn't land.

---

## 5. The dry run is the product

Before anything can be approved, the composer runs the real policy engine over the real audience and shows the block breakdown. On this build, expect early campaigns to look like this:

```
Audience                          38,400
Will receive                      11,950

Not receiving                     26,450
  No WhatsApp opt-in              17,300   ← Phase 2 opt-in capture is still ramping
  WhatsApp capability unknown      5,100   ← they haven't had an order-tracking message yet
  Frequency cap                    2,300
  Global holdout                   1,750
```

The large "no opt-in" bucket is the correct result, not a bug. It shows how much of the list the storefront opt-in work has reached so far. Show it plainly. It is the argument for investing in Phase 2 surfaces rather than for loosening policy.

---

## Acceptance criteria

- [ ] MFA enforced for every role above `ANALYST`; refresh-token reuse revokes the family (tests from `03-auth-and-rbac.md`)
- [ ] "Halt all marketing" stops the next marketing send across all pods within 2 seconds, while utility continues
- [ ] A campaign over the approval threshold cannot be started by its author
- [ ] A push campaign dry run excludes stale tokens and shows them as their own bucket
- [ ] A WhatsApp campaign cannot select an `UNKNOWN`-capability identity or a non-APPROVED template
- [ ] The journey inspector reconstructs the full decision history of any send, including blocked ones, from Postgres alone
- [ ] Every mutation has an audit row written in the same transaction

## Exit gate

Two operators run a real festive-style push + WhatsApp campaign end to end on production, with four-eyes approval, and the report reconciles against Meta billing and FCM counts within 2%.
