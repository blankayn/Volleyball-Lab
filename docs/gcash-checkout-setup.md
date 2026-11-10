# GCash checkout setup

Volleyball Lab Pro uses Xendit Hosted Checkout for APK distribution. The default
offer is ₱199 for 30 days. Players make a new GCash payment to renew. Cloudflare
Workers hosts checkout and webhooks; Firebase Auth and Firestore provide the
player identity, offer, and Pro access.

1. A verified player requests checkout. The Worker checks the offer in
   `billingConfig/pro` and creates or reuses a Xendit Payment Session restricted
   to GCash. The app opens the Xendit URL.
2. When Xendit reports completion, the Worker validates the callback token
   and looks up the session and captured payment through Xendit's API. The
   Worker matches the session, order, Philippine peso amount, and GCash channel
   before granting Pro. A duplicate callback cannot extend Pro twice.
3. The app reads the entitlement from Firestore. Reopening checkout can also
   recover a completed payment if its callback has not arrived.

## Activate checkout

1. Create a [Xendit account](https://dashboard.xendit.co/register/1), complete
   merchant verification, and activate [GCash](https://docs.xendit.co/docs/gcash)
   for live payments. Try a hosted session in Xendit's Test Mode first using the
   [one time payment guide](https://docs.xendit.co/docs/payment-1). The production
   Worker only serves live checkout links.
2. Keep `billingConfig/pro.enabled` set to `false`. Deploy the separate
   payment Worker from the repo root:

   ```sh
   npm exec --prefix ai-worker -- wrangler deploy --config payment-worker/wrangler.jsonc
   ```

   The Worker needs `FIRESTORE_SERVICE_ACCOUNT`, a dedicated service account
   JSON for `volleyball-lab` with Cloud Datastore User access to the database,
   and `FIREBASE_WEB_API_KEY`, the project's Firebase client API key.
3. In the Xendit live dashboard, create a server [secret API key](https://docs.xendit.co/docs/api-keys)
   with permission to create sessions and read payments. Under
   **Settings → Developers → Webhooks**, use the live verification token
   and set **Payment Session – Completed** to:

   `https://volleyball-lab-payments.volleyball-lab-session-ai.workers.dev/xenditWebhook`

   Set the following secrets with Wrangler's prompts or through Cloudflare:

   ```sh
   npm exec --prefix ai-worker -- wrangler secret put XENDIT_SECRET_KEY --config payment-worker/wrangler.jsonc
   npm exec --prefix ai-worker -- wrangler secret put XENDIT_WEBHOOK_TOKEN --config payment-worker/wrangler.jsonc
   ```

4. Deploy the current Firestore rules and install the Xendit APK for the
   controlled purchase. Set the offer in Firestore:

   | Field | Value |
   | --- | --- |
   | `enabled` | Boolean. Enable when ready to accept payments. |
   | `liveMode` | Boolean `true`. |
   | `priceCentavos` | Integer PHP centavos. Use `19900` for ₱199. |
   | `durationDays` | Integer from 1 to 365, or 0 for permanent access. Use `30` for this offer. |
   | `returnUrl` | HTTPS return page, such as `https://volleyball-lab.web.app/payment-return.html`. |

   Confirm the payment grants Pro to the paying account. Edit price and
   duration in Firestore without rebuilding the APK. Open sessions retain
   their original terms. Switching `enabled` off pauses new checkout
   requests; already issued sessions can still complete.

If a player has an unresolved PayMongo order from an older build, reconcile
it in PayMongo before clearing its pending pointer. Reconcile refunds and
disputes in Xendit and adjust any affected entitlement through trusted
Firebase administration.

## Provider references

- [Create a Payment Session](https://docs.xendit.co/apidocs/create-session)
- [Read the status of a Payment](https://docs.xendit.co/apidocs/get-payment)
- [Verify webhook deliveries](https://docs.xendit.co/docs/handling-webhooks)
- [Cloudflare Worker secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
