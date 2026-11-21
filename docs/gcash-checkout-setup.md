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

The Xendit secret key decides which money the checkout takes. A development key
(`xnd_development_…`) works as soon as the Xendit account exists, before merchant
verification: the same checkout, webhook and Pro grant run, and Xendit settles
the GCash payment without charging a wallet. A production key
(`xnd_production_…`) needs a verified merchant with
[GCash](https://docs.xendit.co/docs/gcash) activated, and takes real payments.
Nothing in the app or the Worker changes between the two.

1. Create a [Xendit account](https://dashboard.xendit.co/register/1). Pick the
   dashboard environment (Test or Live) that matches the key you will use, and
   do every step below in that environment.
2. Keep `billingConfig/pro.enabled` set to `false`. Deploy the separate
   payment Worker from the repo root:

   ```sh
   npm exec --prefix ai-worker -- wrangler deploy --config payment-worker/wrangler.jsonc
   ```

   The Worker needs `FIRESTORE_SERVICE_ACCOUNT`, a dedicated service account
   JSON for `volleyball-lab` with Cloud Datastore User access to the database,
   and `FIREBASE_WEB_API_KEY`, the project's Firebase client API key.
3. In the Xendit dashboard, create a server [secret API key](https://docs.xendit.co/docs/api-keys)
   with permission to create sessions and read payments. Under
   **Settings → Developers → Webhooks**, copy the verification token
   and set **Payment Session – Completed** to:

   `https://volleyball-lab-payments.volleyball-lab-session-ai.workers.dev/xenditWebhook`

   Set the following secrets with Wrangler's prompts or through Cloudflare:

   ```sh
   npm exec --prefix ai-worker -- wrangler secret put XENDIT_SECRET_KEY --config payment-worker/wrangler.jsonc
   npm exec --prefix ai-worker -- wrangler secret put XENDIT_WEBHOOK_TOKEN --config payment-worker/wrangler.jsonc
   ```

4. Deploy the current Firestore rules and install a current APK. Set the offer
   in Firestore:

   | Field | Value |
   | --- | --- |
   | `enabled` | Boolean. Enable when ready to accept payments. |
   | `priceCentavos` | Integer PHP centavos. Use `19900` for ₱199. |
   | `durationDays` | Integer from 1 to 365, or 0 for permanent access. Use `30` for this offer. |
   | `returnUrl` | HTTPS return page, such as `https://volleyball-lab.web.app/payment-return.html`. |

   Confirm the payment grants Pro to the paying account. Edit price and
   duration in Firestore without rebuilding the APK. Open sessions retain
   their original terms. Switching `enabled` off pauses new checkout
   requests; already issued sessions can still complete.

   Players need a verified email address to check out. The `.test` demo
   accounts cannot verify one, so pay from an account with a real inbox.

## Switching from a development key to a production key

Sessions created with one key cannot be read with the other. Before replacing
`XENDIT_SECRET_KEY` and `XENDIT_WEBHOOK_TOKEN`, set `enabled` to `false`, delete
the `billingPending` documents, and remove any `entitlements` documents that
development payments granted (`source` is `xendit_gcash`; check `billingOrders`
for the date). Then point the live webhook at the same URL and enable checkout.

If a player has an unresolved PayMongo order from an older build, reconcile
it in PayMongo before clearing its pending pointer. Reconcile refunds and
disputes in Xendit and adjust any affected entitlement through trusted
Firebase administration.

## Provider references

- [Create a Payment Session](https://docs.xendit.co/apidocs/create-session)
- [Read the status of a Payment](https://docs.xendit.co/apidocs/get-payment)
- [Verify webhook deliveries](https://docs.xendit.co/docs/handling-webhooks)
- [Cloudflare Worker secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
