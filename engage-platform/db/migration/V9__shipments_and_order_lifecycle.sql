-- V9: P1 gaps — shipments and courier events (P1-T02), order cancellation and
-- refunds (P1-T04), per-location inventory (P1-T03), last-seen prices (P1-T04).
-- One migration for all of P1's gap tasks. Added with P1-T03; the shipment and
-- refund tables wait for their handlers.

CREATE TABLE shipments (
    id               BIGSERIAL PRIMARY KEY,
    order_id         TEXT,                               -- no FK: fulfillments/create can beat orders/create
    fulfillment_id   TEXT        UNIQUE,                 -- Shopify fulfillment id
    awb              TEXT,
    carrier          TEXT,
    tracking_url     TEXT,
    status           TEXT        NOT NULL DEFAULT 'created'
                     CHECK (status IN ('created','in_transit','out_for_delivery',
                                       'delivered','ndr','rto','cancelled')),
    status_at        TIMESTAMPTZ NOT NULL,               -- time of the courier event, not ours
    ndr_reason       TEXT,
    ndr_attempts     INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX shipments_awb_uq ON shipments (carrier, awb) WHERE awb IS NOT NULL;

CREATE TABLE shipment_events (
    shipment_id  BIGINT      NOT NULL REFERENCES shipments(id),
    source_event TEXT        NOT NULL,                   -- aggregator's event id: dedupe key
    status       TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    raw          JSONB       NOT NULL,
    PRIMARY KEY (shipment_id, source_event)
);

ALTER TABLE orders
    ADD COLUMN cancelled_at      TIMESTAMPTZ,
    ADD COLUMN cancel_reason     TEXT,
    ADD COLUMN refunded_paise    BIGINT NOT NULL DEFAULT 0 CHECK (refunded_paise >= 0);
-- financial_status already exists (V3).

CREATE TABLE order_refunds (              -- dedupe for refunds/create
    refund_id    TEXT PRIMARY KEY,
    order_id     TEXT NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_levels (           -- per-location availability, for P1-T03
    inventory_item_id TEXT NOT NULL,
    location_id       TEXT NOT NULL,
    available         INT  NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (inventory_item_id, location_id)
);

-- Not in the original plan: variant_restocked carries product_id, and
-- inventory_state (V3) caches the item -> variant mapping without it.
ALTER TABLE inventory_state ADD COLUMN product_id TEXT;
CREATE INDEX inventory_state_variant_idx ON inventory_state (variant_id);

CREATE TABLE variant_prices (             -- last seen price, for price_dropped (P1-T04)
    variant_id  TEXT PRIMARY KEY,
    product_id  TEXT NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    updated_at  TIMESTAMPTZ NOT NULL
);
