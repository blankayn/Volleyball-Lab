# Panel compliance — defence of 24 November 2026

| Panel comment | What changed | Where to see it |
|---|---|---|
| Revise assessment method; not a pre-defined style; no bias | Structured questions, the same for everyone; the result is written by AI from that athlete's own answers and measured results, and every line shows what it came from. Position never hides a skill. `docs/ai-assessment.md` | Sign-up, and Profile → Settings → Training & skills → Retake the assessment |
| Revise functionality (checking / taking multiple skills) | All five skills are assessed, and the player can choose more than one to work on | Last page of the skill check |
| Suggest AI next skills | The AI's weekly plan: focus skills, drills with sets and reps, reasons and goals, rewritten from real performance | Home → This week's plan |
| (Team's addition) Adaptive coaching | The AI coach learns from each player's right and wrong reps and changes live coaching and results. `docs/adaptive-coaching.md` | Live session opening; session report → AI coach: what to do next |
| Assignment from coach on the home dashboard | Open assignments, with due date and a button to continue | Home, above the suggestions |
| Coach profile – remove | The cover photo is gone from My coach and from the coach's public page. "Up next" now says "Assigned by your coach". | My coach |
| How does the player know a coach is legitimate? | Each coach shows **Verified** or **Not verified**. A coach becomes verified by passing automatic checks, or by the project owner. Players are warned before they send a request to a coach who is not verified. | Coach directory, coach page, My coach. Coaches: website → Account → Verification |
| Trace / justify statistical values and computational ratings | Each report shows its own working, and `docs/score-computation.md` traces every formula, weight and limit | Session report → How this score was computed |

## To say plainly if asked

- Two ways to be verified: the automatic checks, or the project owner who knows the coach (`coachVerified`, set in the Firebase console only). The system cannot check an ID.
- Reference ranges are provisional. A published study supports two of the receiving
  ranges; it suggests nothing for setting and blocking. See `docs/score-computation.md`, section 5.
- The position filter still exists in two old places: the fallback for players who signed
  up before this change, and drill recommendations. It no longer affects next skills.

## Before players can use it

1. Deploy `firestore.rules` first. Until then the new skill check and coach checks cannot be saved.
2. Deploy `ai-worker`. Until then Ask AI falls back to the app's own ordering.
3. Deploy the website, then each coach confirms their email in Account → Verification.
4. Install the new app build.

Coaches show **Not verified** until step 3 is done, except those the owner verified.
