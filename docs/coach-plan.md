# Coach plan, names and registration (25 November 2026)

## Free and Pro for coaches

| Feature | Free | Pro | Enforced by |
|---|---|---|---|
| Students | 3 | Unlimited | Firestore rules |
| Session review and written feedback | Included | Included | |
| Drills and chat | Included | Included | |
| Photos in feedback | No | Included | Firestore rules |
| Right / Wrong marks on reps | No | Included | Firestore rules |
| AI session explanations (website) | No | Included | Website only |

Pro is the same offer and the same `entitlements/{uid}` record as for players
(`billingConfig/pro`, currently ₱199 for 30 days, checkout disabled).

`coachSlots/{coach}` lists the players holding a place, because rules cannot count
documents. Accepting a player commits the place and the player's status together. A
name leaves the list only when that player is no longer active. Players who joined before
this change keep their place; a Free coach with more than three keeps them all and cannot
accept more. A coach who never recorded places and edits requests by hand could take up
to three beyond those older players.

To give a coach Pro without payment: Firebase console, `entitlements/{uid}`, `status`
`active`, `expiresAtEpochMs` 0.

## Registration

First, middle and last name are required in the app and on the website. Other people see
the middle name as an initial ("Maria C. Santos"). A new account is signed out and returned
to the login page; nobody goes straight to the dashboard. Google sign-in has no form, so it
still enters directly.

## Coaching role

Specialisation is gone: every coach covers all five skills. It is replaced by the coaching
role: Head coach, Assistant coach, Trainer or PE teacher (`coachingRole`). Old
specialisation values are ignored, and older app versions may still send one.

## Order

Deploy `firestore.rules` first, then the website, then the app. Older app versions cannot
accept players after the rules are live, because they do not record a place.
