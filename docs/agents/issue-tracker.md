# Issue tracker: GitHub

Issues and specs for this repo live in GitHub Issues for `blankayn/Volleyball-Lab`.
Use the `gh` CLI from the repo root. The connected GitHub tools may also be used
for operations they support. If CLI authentication is unavailable, report the
limitation rather than switching trackers silently.

## Conventions

- Create: `gh issue create --repo blankayn/Volleyball-Lab --title "..." --body-file <file>`.
- Read: `gh issue view <number> --repo blankayn/Volleyball-Lab --json number,title,body,labels,comments`.
- List: `gh issue list --repo blankayn/Volleyball-Lab --state open --json number,title,body,labels`; add label filters as needed.
- Comment: `gh issue comment <number> --repo blankayn/Volleyball-Lab --body-file <file>`.
- Apply/remove labels: `gh issue edit <number> --repo blankayn/Volleyball-Lab --add-label "..."` / `--remove-label "..."`.
- Close: `gh issue close <number> --repo blankayn/Volleyball-Lab`.
- Write multiline issue bodies and comments to temporary UTF-8 files and pass `--body-file`.
- Link child tickets as native sub-issues when supported. Otherwise place
  `Part of #<parent>` at the top of the child body and list it in the parent's task list.
- Record blocking edges with GitHub native issue dependencies when supported.
  These APIs require numeric database IDs, not issue numbers or node IDs.
  Otherwise use `Blocked by: #<number>, #<number>` at the top of the child body.
  A ticket is ready when all blockers are closed.

## Pull requests as a triage surface

**PRs as a request surface: no.**

GitHub issues and PRs share a number space. Resolve ambiguous references before acting.

## Skill instructions

When a skill says "publish to the issue tracker", create a GitHub issue.
When a skill says "fetch the relevant ticket", read its GitHub issue.

## Wayfinding operations

- Map: one issue labelled `wayfinder:map`, holding Notes / Decisions-so-far / Fog.
- Child: a linked issue labelled `wayfinder:<type>`
  (`research`, `prototype`, `grilling`, or `task`).
- Frontier: open map children with no open blockers and no assignee, in map order.
- Claim: assign the selected ticket to the driving developer before work.
- Resolve: comment with the answer, close the ticket, and add a summary and link to the map.

