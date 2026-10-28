# GCash checkout setup

Status: app and backend code prepared; merchant activation and live payment testing pending.
Temporary demo: enter `123456` in Subscribe to Pro to save account-scoped
`demoProAccess/{uid}` through Firestore rules. This grants Pro, including coach
requests, without Cloud Functions or a payment account. Remove the demo collection
rules and its `hasPro` branch before launching real payments.
Distribution: APK, as requested. Free detailed reports: 5 per Monday-to-Sunday week.
Owner Pro: Firebase UID `4j06UlYqbrbLzujmfi8VW5yEIOq2`, confirmed for `kirt@gmail.com`.

## Prepared flow

1. A verified, signed-in account requests checkout from `createGcashCheckout`.
2. The server reads the price and access period from `billingConfig/pro`; clients cannot change either.
3. PayMongo hosts GCash checkout. Merchant secret keys stay in Firebase Secret Manager.
4. Only a valid live-mode webhook with a matching paid PHP GCash payment creates Pro entitlement.
5. The Android app observes `entitlements/{uid}`. Browser return or a screenshot does not grant access.

The owner account has Pro independently. Old local demo flags are ignored by the production object graph.

## Merchant setup still required

- Create and verify a PayMongo merchant account and enable GCash.
- Choose the Pro price and access period. No price has been invented or enabled.
- Confirm Firebase Functions billing availability before deployment.
- Set `PAYMONGO_SECRET_KEY` and `PAYMONGO_WEBHOOK_SECRET` with `firebase functions:secrets:set`.
- Deploy the billing Functions after reviewing the code and prototype rules.
- Register the live `checkout_session.payment.paid` webhook at the deployed `paymongoWebhook` URL.
- Set `billingConfig/pro` from trusted administration with fields:
  `enabled`, `liveMode`, `priceCentavos` (integer PHP centavos), `durationDays` (0 permanent or 1–365), `returnUrl` (HTTPS).
- Start with `enabled: false`. Test signed sandbox events separately; sandbox payments never grant real Pro.
- Verify a live checkout, expiry/renewal, duplicate webhook delivery, refund/revocation handling and merchant support process before enabling public sales.

Refunds and disputes require a reviewed revocation policy; automated revocation is not included in this prepared initial flow.
Daily schedules, profile photos and the report ledger are account-scoped local data. The Free allowance is an app feature, not a server-enforced API spending cap.

## Provider references

- [Hosted checkout](https://docs.paymongo.com/docs/payment-channels-hosted-checkout)
- [Webhook signature verification](https://docs.paymongo.com/docs/developer-tools-webhook-setup-management)
- [Firebase HTTP Functions](https://firebase.google.com/docs/functions/http-events)
