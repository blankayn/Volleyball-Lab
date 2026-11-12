# Volleyball Lab password recovery

Android requests a six-digit email code, verifies it, and then uses Firebase's
password-reset SDK to set the new password. Codes expire after ten minutes and
can be verified once, with at most five attempts. Firebase reset actions are
generated only after the email code is verified.

The separate Cloudflare Worker uses Gmail SMTP over TLS on port 465 with the
sender name **Volleyball Lab**. The sender is configured in wrangler.jsonc.
The Gmail account must have 2-Step Verification and an app password. Credentials
belong in Worker secrets.

- GMAIL_APP_PASSWORD contains the sender's Google app password.
- FIREBASE_AUTH_SERVICE_ACCOUNT contains a dedicated service-account JSON
  credential for the volleyball-lab project. Its custom role grants only
  firebaseauth.users.sendEmail.
- RECOVERY_SECRET contains a random secret used to hash codes and rate-limit
  identifiers.

D1 stores the recovery email, hashed code, attempt count and expiry. Hourly cleanup
removes expired data. Per-hour limits allow five code emails per address and ten
per network address. The service also limits outgoing code emails to 100 per day.
Neither passwords nor Firebase action codes are stored in D1. Request logging is
disabled. Accounts do not need to exist to receive a code. A verified code can
reset only its own email's existing Firebase account.

Run node --test auth-worker/test/*.test.mjs from the repository root.
Use the Wrangler installation in ai-worker/node_modules to apply AUTH_DB
migrations and deploy from this directory. Android's passwordRecoveryUrl Gradle
property selects the public Worker endpoint.

Until the Gmail app password and a live delivery test are complete, normal Android
builds leave passwordRecoveryUrl empty and use Firebase's reset email. The user
opens the email link directly. Set passwordRecoveryUrl to the deployed auth Worker
after delivery is verified to enable six-digit codes.

The Android device test PasswordRecoveryDeviceTest uses synthetic recovery
responses and checks the complete screen sequence, confirmation validation,
button spacing and access to the action while editing.
