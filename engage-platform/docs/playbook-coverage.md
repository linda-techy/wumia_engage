# Playbook coverage: "Wumika – Push, WhatsApp & SMS Marketing Playbook" (22 Oct 2026)

What Engage does for each item, as of 22 Oct 2026.
- ✅ built (live after the next deploy where marked *new*)
- ◐ partly
- ❌ not built
- 👤 owner action outside Engage

## 1. Channels and the waterfall

| Playbook | Engage |
|---|---|
| Push first (free), WhatsApp to non-actors, SMS last | ✅ cart recovery (WhatsApp first since 2026-10-28, as most Indian D2C stores run it: WhatsApp 30 min → push +3 h → coupon WhatsApp 24½ h after the first; push first only for shoppers without WhatsApp consent); ✅ campaigns: push, then a WhatsApp follow-up to non-clickers; ✅ checkout: WhatsApp first at 30 min (the phone is known, highest intent), push, WhatsApp offer |
| SMS as fallback / final reminder | ◐ order updates and the payment link only. Marketing SMS needs DLT promotional templates 👤; not built |

## 2. Compliance, cost, opt-ins

| Playbook | Engage |
|---|---|
| DLT: entity, sender ID, templates, brand name | 👤 registration pending (kit: `docs/SMS-DLT-registration.md`); every template ends in -WUMIKA |
| Whitelisted SMS links | ✅ the lint refuses links not on the whitelisted domain |
| Promotional SMS 9 AM–9 PM | ✅ quiet hours 21:00–09:00 for all marketing |
| WhatsApp opt-in and correct categories | ✅ consent ledger; the lint blocks promotional words in utility templates; Meta's verdict is synced and mismatches alerted |
| Meta per-user marketing cap | ✅ WhatsApp marketing at most 1 a day per person; a Meta throttle (131049) moves to the next channel |
| No fake urgency (CCPA dark patterns) | ✅ countdowns run to a fixed end; nothing is sent after it; no "only N left" claims are generated |
| DPDP: consent records, easy opt-out | ✅ every grant with source, wording and time; STOP, unsubscribe links, privacy requests |
| ₹0.86 marketing, utility for order updates | ✅ order updates are utility; spend ledger, daily budgets, campaign budget caps |
| Free 24 h reply window | ✅ service replies are free-form (e.g. *new* welcome reply after START) |
| Click-to-WhatsApp ads: 72 h free | ❌ not modelled (an ads-side setup 👤) |
| Checkout WhatsApp/SMS consent | ✅ *new*: consent at the phone field, not only at the order; ✅ *new*: Shopify's checkbox as WhatsApp + SMS marketing once relabelled 👤 |
| Popup: spin the wheel / "code on WhatsApp" | ❌ |
| WhatsApp chat button, Click-to-WhatsApp ads | ◐ wa.me opt-in bar on iPhone and in-app browsers; ads 👤 |
| "Notify me" back-in-stock / price drop | ✅ back-in-stock waitlist; price drop for items in carts and waitlists |
| Soft push prompt after 2nd page / add-to-cart | ✅ plus *new*: intent moments (2nd look at a piece, exit intent), first-order offer revealed after Allow, the bell, 7-day re-ask, 3 asks max |

## 2b. Segmentation, timing, language, links

| Playbook | Engage |
|---|---|
| RFM | ◐ orders placed, last order; monetary / AOV band needs the nightly profile job ❌ |
| Metro vs Tier 2/3, region festivals | ◐ state and city; no city-tier mapping yet |
| Category browsed, price sensitivity, gender | ◐ category *bought*; browsed, price sensitivity and gender ❌ |
| Peak hours, salary days | ✅ campaigns are scheduled by the operator (IST) |
| Caps: push 1–2/day in a sale, WhatsApp 2–3/week | ✅ push marketing 3/day (Settings), WhatsApp 1/day; adjustable |
| Hinglish and regional languages | ❌ by the owner's earlier rule "templates English only" (enforced by a build test). Say if the playbook overrides it |
| Auto-apply discount links | ✅ *new*: composer field "Discount code (applies itself)"; coupon messages open with the code applied |
| UTMs per channel and campaign | ✅ on every link; *new*: placed inside the discount redirect so they survive it |
| Cart-restore links | ◐ the reminder opens /cart (empty on another device); Shopify cart permalinks ❌ |
| Countdown on the landing page | ❌ theme block not built; push has the countdown poster (*new*) |

## 3. Always-on flows

| Flow | Engage |
|---|---|
| Welcome series (day 0, 2, 5 with first-order offer) | ✅ *new* (push, a free WhatsApp reply for START, offer push day 5, WhatsApp offer day 6) |
| Browse abandonment | ✅ push, then WhatsApp the next day for strong interest (2+ views) |
| Cart abandonment | ✅ *new* order and coupon; SMS at 48 h ❌ (DLT) |
| Checkout abandonment | ✅ 30 min, WhatsApp |
| Order updates | ✅ WhatsApp utility, *new* product photo on the confirmation; SMS waits for DLT 👤 |
| Delivery issue with buttons | ✅ failed-delivery WhatsApp with quick replies → Support requests |
| Review request | ✅ after delivery with quick replies; photo-review reward ❌ |
| Cross-sell 7–14 days after delivery | ❌ |
| Replenishment | — not for jewellery |
| Back in stock / price drop | ✅ push + WhatsApp, *new* product photo |
| Win-back 60/90/120 | ◐ 90 days, then an offer 14 days later; 60 and 120 steps ❌ |
| Birthday / anniversary | ❌ no birthday data is collected; first-order anniversary not built |
| Loyalty points expiring | — no loyalty programme |
| Referral ask after a 4–5★ review | ❌ |

## 4. FOMO and conversion techniques

| Playbook | Engage |
|---|---|
| Time urgency, reminder sequence | ✅ *new* push countdown: {time_left}, countdown poster, last-chance pushes at 24/3/1 h, never after the end; ✅ *new* WhatsApp `campaign_last_call_v1` |
| WhatsApp coupon with copy-code button | ✅ *new* (`campaign_*`, `cart_offer`, `welcome_offer`) |
| WhatsApp Limited-Time Offer template type | ❌ (copy-code plus a stated end instead) |
| Personal codes 24–48 h | ◐ built (single-use codes), but they need Shopify Admin API access, blocked on the live store (`shop_not_permitted`: needs an app install with an offline token 👤/to build). Shared codes work now |
| Flash sales, happy hours, delivery cutoff | ✅ campaigns with a timer; the cutoff is campaign copy |
| Scarcity ("only 4 left"), social proof counts | ❌ (would need live stock / sales counts) |
| Early access for subscribers / VIPs | ✅ *new* `campaign_early_access_v1` + segments (e.g. orders placed ≥ 1) |
| Tiered offers, BOGO, free gift | 👤 set up in Shopify; campaigns announce them |
| Gamification, WhatsApp Flows quizzes | ❌ |
| WhatsApp images + buttons | ✅ *new* image headers (product photo, campaign poster), URL + copy-code buttons; carousels ❌ |
| Push hero image + action buttons | ✅ poster; *new* "Shop now", "View bag" and "View the piece" buttons |

## 5–8. Campaigns, calendar, Diwali

- All campaign types (sales, launches, seasonal, events, content) can be run from Campaigns. WhatsApp ones need an approved `campaign_*` template. Five exist (*new*), submitted with the next `wa-submit`.
- The Diwali push drafts exist (Dhanteras teaser, Dhanteras offer, Diwali gifting, Happy Diwali, Bhai Dooj).
- The playbook's plan starts earlier: teaser and opt-in drive 23–27 Oct, VIP early access 28–29 Oct, main sale with salary day 30 Oct–3 Nov, delivery cutoff 1–3 Nov. The drafts should be moved to it once the offers are known 👤.

## 9. Metrics

| Playbook | Engage |
|---|---|
| Delivery, read/open, CTR, conversion, revenue per message, cost per order per channel | ✅ Analytics (*fixed*: it crashed while there were no sends) |
| Opt-out/block rate, WhatsApp quality, opt-in rate | ✅ Dashboard |
| Holdout to measure lift | ✅ 10% per marketing journey, per-campaign holdout, Reports → lift |
| Repeat purchase rate | ❌ no report yet |
