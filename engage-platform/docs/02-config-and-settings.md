# 02 — Config and settings

This is the part most admin consoles get wrong, so it comes before the CRUD.

## The problem with a settings table

The obvious design is a `settings` table with a `value` column that an admin edits. It fails the first time it matters.

A customer complains they got a marketing WhatsApp at 22:40. You look at `settings`: quiet hours are 21:00–09:00. So the message should have been blocked. Was there a bug? Did someone change the setting and change it back? Was the send actually exempt? You cannot tell, because the row only knows its current value. The evidence was overwritten.

Same problem with cost. Spend spikes on a Tuesday. Was the daily budget raised? By whom? For how long? A mutable row cannot answer.

Settings that govern whether a message may be sent are **evidence**, not preferences. They get the same treatment as the consent ledger: append-only, with the actor and the reason attached.

## The model

Three tables, one view.

**`config_keys`** — the registry. Every setting is declared here with its type, validation schema, risk level and minimum role. Adding a setting is a migration, not a free-text row someone invents during an incident. This is also what the Angular UI renders from, so the form is generated rather than hand-written per setting.

**`config_versions`** — append-only history. One row per change, carrying `changed_by`, `approved_by`, a mandatory `reason`, and an effective-dated window. `UPDATE` and `DELETE` are blocked by a database rule, not just by the service layer, so an ORM bug or a stray psql session cannot rewrite history.

**`config_snapshots`** — the immutable set of version ids in force at a given moment, keyed by a fingerprint hash so identical states deduplicate. Every `sends` row points at one.

**`config_current`** — a view resolving what is in force right now.

## Resolution

Keys are scoped, and resolution is most-specific-wins:

```
cap.whatsapp.marketing.1d  selector='*'              →  1   (global default)
cap.whatsapp.marketing.1d  selector='vip_early'      →  2   (journey override)
```

A journey named `vip_early` resolves to 2; everything else to 1. Within the same selector, the row with the latest `effective_from` that has started and not expired wins.

## Effective dating earns its keep at festive season

```
key:            cap.whatsapp.marketing.1d
selector:       *
value:          2
effective_from: 2026-10-17 00:00 IST
effective_to:   2026-10-23 00:00 IST
reason:         "Diwali week — approved by growth + finance, reverts automatically"
```

The cap rises for Diwali week and returns to 1 on its own. Nobody has to remember to change it back, which is the actual failure mode: elevated caps outlive the campaign that justified them and quietly become permanent, and three months later your block rate is up and nobody knows why.

## Snapshots and why sends point at them

Resolving config on every send decision would mean a handful of extra queries per message. Instead the policy engine holds a resolved snapshot in memory, refreshed on a short interval and on an explicit invalidation.

```java
@Singleton
public class ConfigResolver {

    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>();

    /**
     * Rebuild the snapshot from config_current, fingerprint it, and reuse the
     * existing row when nothing changed. A stable id means every send during a
     * quiet period shares one snapshot row rather than creating millions.
     */
    @Scheduled(fixedDelay = "30s")
    void refresh() {
        var rows = repo.currentValues();
        var fingerprint = sha256(rows.stream()
                .map(ConfigRow::versionId).sorted().toList().toString());

        var snapshot = snapshots.findByFingerprint(fingerprint)
                .orElseGet(() -> snapshots.save(ConfigSnapshot.of(fingerprint, rows)));

        current.set(snapshot);
    }

    public ConfigSnapshot snapshot() {
        return current.get();
    }
}
```

Thirty seconds of staleness is acceptable for caps and budgets. It is **not** acceptable for a kill switch, so those bypass the cache — see below.

The cost of this design is one extra `BIGINT` column on `sends`. The benefit is that three years later you can reconstruct exactly which rules applied to any individual message.

## Risk tiers

Every key carries a risk level that drives the UI and the approval path.

| Risk | Meaning | UI treatment | Approval |
|---|---|---|---|
| `SAFE` | Cosmetic, no delivery impact | Save inline | None |
| `GUARDED` | Changes who receives messages | Confirm dialog, reason required | None |
| `CRITICAL` | Changes spend or compliance posture | Diff preview + typed confirmation | Second approver |

`CRITICAL` covers WhatsApp caps, daily budgets, the global holdout percentage, and the approval threshold itself. Note that last one: without it, an operator could lower the approval threshold to zero and then approve their own spending. Self-referential settings need the same protection as the things they protect.

A `CRITICAL` change is written to **`config_proposals`**, not to `config_versions`. Only when a second operator with `CONFIG_ADMIN` approves is a `config_versions` row **inserted**, with `approved_by` set. The proposal is visible in the UI as pending the whole time.

(An earlier draft of this document had approval *update* a pending `config_versions` row. That breaks append-only, and worse: `config_current` resolves by `effective_from` alone, so an unapproved future-dated row would silently take effect when its start time arrived. The database now enforces both rules. `config_versions` rejects any UPDATE or DELETE, and `config_proposals` has a CHECK constraint that the approver differs from the proposer.)

## The kill switch is different

Kill switches must not wait 30 seconds and must not be cached.

```java
@Singleton
public class KillSwitch {

    /**
     * Read-through with a 1s TTL, and a LISTEN/NOTIFY invalidation on top.
     * If someone hits "stop all WhatsApp" during an incident, the next message
     * must not go out. Thirty seconds of stale cache is thirty seconds of
     * messages you explicitly said to stop.
     */
    public boolean channelHalted(Channel channel) {
        return cache.get("halt:" + channel, Duration.ofSeconds(1),
                () -> repo.isHalted(channel));
    }
}
```

Postgres `LISTEN`/`NOTIFY` propagates the invalidation to every pod immediately; the 1s TTL is the fallback if a listener has dropped. Belt and braces, because the failure mode is "we kept sending during an incident".

Three switches, all one click from the dashboard header:

- **Halt channel** — stop all sends on WhatsApp / push / email / SMS
- **Halt journey** — stop one journey, leave the rest running
- **Halt all marketing** — utility and authentication keep flowing, so orders still confirm and OTPs still land

The third is the one you want at 2am. It stops the revenue-negative traffic without breaking transactional flows.

## Rendering the form from the registry

Because `config_keys` carries `value_type` and `json_schema`, the Angular settings screen is generated. Adding a setting means a migration plus an enum constant; no frontend change.

```json
{
  "key": "cap.whatsapp.marketing.1d",
  "scope": "CHANNEL",
  "valueType": "INT",
  "jsonSchema": { "type": "integer", "minimum": 0, "maximum": 2 },
  "label": "WhatsApp marketing / day",
  "helpText": "Keep below Meta's own per-user limit so you never hit it.",
  "risk": "CRITICAL",
  "minRole": "CONFIG_ADMIN",
  "current": { "value": 1, "selector": "*", "since": "2026-08-01T00:00:00Z",
               "changedBy": "priya@brand.in", "reason": "Post-EOSS normalisation" },
  "pending": null
}
```

Note the `maximum: 2` on that schema. Meta's own per-user marketing limit is the ceiling; the validator refuses a value above it rather than letting an operator set 5 and discover the hard way that Meta silently drops the excess while counting it against your quality rating. Encode the platform's limits as validation, not as documentation.

## Validation happens server-side, always

The Angular form validates for ergonomics. The server validates for correctness, against the same `json_schema`, on every write. An admin console is an authenticated HTTP API and will eventually be called by a script.

```java
@Post("/config/{key}")
@Secured("CONFIG_ADMIN")
public ConfigVersion update(@PathVariable String key,
                            @Valid @Body ConfigUpdate body,
                            Principal principal) {
    var def = configKeys.require(key);
    schemaValidator.validate(def.jsonSchema(), body.value());   // never trust the client

    if (def.risk() == Risk.CRITICAL && body.approvedBy() == null) {
        return proposals.propose(def, body, principal);          // needs four eyes
    }
    return configs.apply(def, body, principal);
}
```

## What does not belong here

Secrets. API tokens, the Meta system-user token, the SES credentials, the database password. Those live in the secret manager and are injected as environment variables. If a secret is editable from the admin UI, the admin UI is now a credential exfiltration tool for anyone who phishes an operator.

The console can show *whether* a credential is configured and when it was last rotated. It cannot show or set the value.
