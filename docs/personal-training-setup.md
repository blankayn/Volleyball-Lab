# Personal training setup and progress

Progress compares seven scored sessions of one skill. Bars open saved sessions.
Zero scores remain distinct from missing measurements. Next-focus tips require
existing measured evidence; a score change is not an ability classification.

The five setup cards collect a goal, experience, one adaptive follow-up, weekly
schedule and editable confirmation. English prompts accept Taglish notes. Only
one follow-up and one summary AI request are attempted per opened setup; offline,
timeouts, quotas or invalid output switch to Quick setup. See ai-worker/README.md
for the authenticated endpoint and approved question catalog. Providers receive
only setup answers; users are asked to omit personal/contact information. Raw AI
requests and responses are not stored.

Confirmed plans and preferences sync privately in profiles/{uid}, preserving
explicit legacy ratings and unrelated identity/media/session data. Initial cloud
restoration is bounded. Skip preserves existing choices. Existing users may open
Profile > Training > Refresh my plan without being forced through setup again.

Deploy the Firestore rules and AI Worker before distributing Android previews.
Verification covers same-skill score derivation, zero/missing values, AI fallbacks,
account switches, saved-plan restoration, private access and small-screen layouts.
