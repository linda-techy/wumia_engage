# SMS: DLT and MSG91 registration

Every commercial SMS in India must use a DLT-registered sender header and a DLT-registered template; operators drop anything else. Engage's four SMS templates are fallbacks for order updates and the failed-payment link. Until each has its DLT and MSG91 IDs, Engage refuses to send it (`dlt_pending`). The MSG91 key is already installed on production.

## 1. DLT portal (Jio TrueConnect, or any operator's)

1. **Entity (Principal Entity):** register WUMIKA's business (PAN, GST, authorisation letter). You receive an Entity ID.
2. **Header (sender ID):** `WUMIKA` (6 letters), type **Service Implicit**.
3. **URL whitelisting:** whitelist `https://wumika.com` (the links use `https://wumika.com/apps/push/…`). Operators block SMS with links that are not whitelisted.
4. **Content templates:** category **Service Implicit**, header `WUMIKA`. Paste each text exactly; `{#var#}` is DLT's variable. Each value is at most 30 characters (ours are at most 19).

| Engage key | Template text for DLT |
|---|---|
| `sms_order_shipped` | `Your WUMIKA order {#var#} has shipped with {#var#}. Track it here: https://wumika.com/apps/push/t/{#var#} -WUMIKA` |
| `sms_out_for_delivery` | `Your WUMIKA order {#var#} is out for delivery today. Track it here: https://wumika.com/apps/push/t/{#var#} -WUMIKA` |
| `sms_ndr` | `Delivery of your WUMIKA order {#var#} by {#var#} did not go through today. Track the next attempt: https://wumika.com/apps/push/t/{#var#} -WUMIKA` |
| `sms_payment_link` | `Your payment of Rs {#var#} to WUMIKA did not go through (ref {#var#}). Complete it here: https://wumika.com/apps/push/pay/{#var#} -WUMIKA` |

Each approved template gets a **19-digit DLT template ID**.

## 2. MSG91

1. **Sender ID:** add `WUMIKA` with the DLT Entity ID.
2. **Templates (Flow / DLT templates):** create one per row below. Paste the DLT template ID and the same text, but name the variables exactly as shown. Engage sends values by these names.

| Engage key | MSG91 template text |
|---|---|
| `sms_order_shipped` | `Your WUMIKA order ##order_no## has shipped with ##courier##. Track it here: https://wumika.com/apps/push/t/##track_ref## -WUMIKA` |
| `sms_out_for_delivery` | `Your WUMIKA order ##order_no## is out for delivery today. Track it here: https://wumika.com/apps/push/t/##track_ref## -WUMIKA` |
| `sms_ndr` | `Delivery of your WUMIKA order ##order_no## by ##courier## did not go through today. Track the next attempt: https://wumika.com/apps/push/t/##track_ref## -WUMIKA` |
| `sms_payment_link` | `Your payment of Rs ##amount_plain## to WUMIKA did not go through (ref ##payment_id##). Complete it here: https://wumika.com/apps/push/pay/##pay_ref## -WUMIKA` |

   Turn MSG91's link shortening **off** for these templates: the links must stay on the whitelisted domain.
3. **Delivery reports:** set the webhook to `https://engage.wumika.com/webhooks/sms?token=<MSG91_DLR_TOKEN>`. Read the token on the server with `grep ^MSG91_DLR_TOKEN= /opt/wumika/.env`.

## 3. Hand back

Send the four pairs (DLT template ID, MSG91 template ID). They go into `dlt_template_id` and `msg91_template_id` in `templates/sms/*.yaml`, the linter checks them, and a deploy turns SMS on.
