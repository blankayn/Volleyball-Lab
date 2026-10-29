# Restricted coaching inbox

Android groups `coachingQuestions` by player. Rows sort unread conversations first,
then by newest message time. Header, row and dashboard badges count incoming
messages, rather than unresolved questions. Own messages do not count as unread.
Roster names and initials are used; private player profile images are not exposed.

Each question retains its original topic, introduction/reviewed-session context,
initial text and coach answer. Optional `followUp1` and `followUp2` maps contain
`text`, `createdAt`, `answer` and `answeredAt`. Timestamps for new messages and
answers are server timestamps. Turns alternate: a player waits for a coach reply
before adding another follow-up. Only the assigned coach can answer. The coach
cannot initiate an unsolicited thread.

`resolvedAt` closes an answered thread. Either participant can resolve after all
outstanding questions have answers. Resolved and disconnected threads are read
only, apart from each participant's own read marker. Existing questions remain
readable and can be answered/resolved without rewriting their history.

`coachReadAt` and `playerReadAt` are monotonic, participant-owned timestamps. A
client records the newest incoming message it actually loaded, not the current
clock time. A message arriving later must remain unread. The web portal marks a
loaded thread read only when the marker after its messages enters the viewport.

`coachingLocks/{coachUid}_{playerUid}` contains only `coachUid`, `playerUid` and
`questionId`. New questions atomically acquire this connection lock. It can move
to another question only after the previous question was resolved. New IDs are
Firestore-generated; review IDs remain unchanged. Existing pre-lock questions
are grandfathered history: Android surfaces outstanding legacy questions before
offering a new one; all newly created questions require the server lock. No
automatic migration resolves or sends messages in existing conversations.

Rules reject attachments/unknown fields, whitespace-only text, URLs/common domain
names, phone-number patterns, email/handle patterns and known social-platform
names. New questions require an active connection and an approved topic. A
session topic also requires that player's reviewed submission. Questions are
limited to 300 characters and responses to 1,000 characters. These are structural
and pattern restrictions; they do not infer whether every sentence concerns
training. The one-question gate and coach responses keep the exchange bounded.

First-response metrics still measure the first coach answer. Notification events
have distinct identities for follow-ups and their answers. Failed sends retain
drafts until an acknowledged message arrives. Account cleanup includes the new
lock documents; deployment of those existing functions requires Firebase billing.

Rollout: new Firestore rules and the compatible web portal were deployed. Older
Android builds cannot create new questions without locks and must be updated.
The account-cleanup function update was blocked by the project's closed billing
account; no billing plan was enabled. Core messaging uses Firestore directly.

Verification: final Firestore regression suite 57/57, inbox-model unit tests 5/5, Android layout/interaction checks 5/5. Native screenshots reviewed; web production build passed. Storage-only tests were outside this change and require their separate emulator.
