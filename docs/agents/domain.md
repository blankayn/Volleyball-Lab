# Domain Docs

Volleyball Lab uses a single domain documentation context shared by the
Android player app, coach web portal, and Firebase backend.

## Before exploring

- Read the root `GLOSSARY.md`, if present.
- Read decisions under `docs/adr/` that affect the area being changed.
- Use existing product documents, including `README.md`,
  `docs/coach-cloud-contract.md`, and `docs/manuscript-gap-audit.md`,
  when relevant. Their verification records are historical evidence;
  distinguish implemented behavior from physical-device or research validation.

If the glossary or ADRs do not exist, proceed silently.
The domain-modeling skill creates them lazily when terms or decisions are resolved.
Do not create empty glossaries or placeholder ADRs during setup.

## Vocabulary

Use the glossary's domain terms in tickets, proposals, hypotheses, and test names.
If a needed concept is absent, check existing project terminology before introducing it.

## Decisions

Record durable decisions under `docs/adr/` with numbered descriptive filenames.
Explicitly identify any proposal that contradicts an existing ADR and explain
why revisiting that decision is warranted.

