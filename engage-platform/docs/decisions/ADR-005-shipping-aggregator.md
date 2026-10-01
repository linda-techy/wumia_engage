# ADR-005: Shipping aggregator is Shiprocket

**Status:** accepted, 2026-09-30 · **Decides:** the courier webhook adapter (P1-T02) and the NDR reply API (P5-T07)

## Context

`order_tracking` (P4) and `ndr_rescue` (P5) need courier states Shopify does not reliably carry: out for delivery, delivered, and failed delivery attempts (NDR). WUMIKA ships through Shiprocket.

## Decision

Shiprocket's tracking webhook is the courier source, behind `CourierAdapter` (`ShiprocketAdapter`). Shopify `fulfillments/create` and `fulfillments/update` give the order and the AWB (tracking number) and join the two by AWB.

What is Shiprocket-specific (from apidocs.shiprocket.in, "Webhooks"), and lives only in `ShiprocketAdapter`:

| Item | Shiprocket |
|---|---|
| Setup | Shiprocket → Settings → API → Webhooks: URL, toggle on, security token |
| URL | `https://<ingest host>/webhooks/courier`. Shiprocket rejects URLs containing `shiprocket`, `kartrocket`, `sr` or `kr` |
| Auth | the security token arrives as `x-api-key`; **no signature**. Engage requires it (`COURIER_WEBHOOK_TOKEN`; blank refuses every call) and compares in constant time |
| Response | 200 |
| Event id | none: the inbox dedupes retries on a hash of the body; `shipment_events` dedupes on AWB + status + courier time |
| Status | `shipment_status` label (mapped first) and `shipment_status_id`. Confirmed ids: 6 shipped, 7 delivered, 9 RTO initiated, 10 RTO delivered, 42 picked up. Undelivered/NDR, out for delivery, cancelled, RTO map by label. Lost/damaged map to nothing (a human decides) |
| Time | `current_timestamp`, `dd MM yyyy HH:mm:ss`, IST; else the latest scan's `date` |
| NDR reason | the latest scan's `activity` text |

## Consequences

- Only the adapter changes if the aggregator changes.
- The status mapping is by label because Shiprocket does not publish its id table and its two id fields differ. Tighten it with Shiprocket's **Test Webhook** once connected, and with real payloads.
- **Never point the live store's Shiprocket webhook at the dev server.** Its payloads are real customers' parcels. Dev is tested with the fixtures (`courier_shiprocket_*.json`) or a Shiprocket test account.
- The app needs `read_fulfillments` for the fulfillment webhooks.
- P5-T07 (NDR reply to the courier) will use Shiprocket's NDR action API; decided there.
