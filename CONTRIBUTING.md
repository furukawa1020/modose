# Contributing to MODOSE

Every change must preserve the product boundary and the false-success safeguards in
`docs/PRODUCT_REQUIREMENTS.md` and `docs/ARCHITECTURE.md`.

## One Issue, one PR

- Start from exactly one backlog item in `docs/BACKLOG.md`.
- Create one GitHub Issue for that MODOSE ID.
- Open one PR that closes that GitHub Issue.
- Change no more than one user-visible behavior in a PR.
- Keep unrelated refactors, generated migrations, and dependency upgrades separate.
- Do not add placeholder UI, unused code, disabled tests, or untracked TODOs.

## Naming

- Branch: `m-###-short-slug`
- PR: `type(scope): imperative summary [M-###]`
- Commit: use the PR title when the PR contains one commit

Example:

```text
m-083-update-move-guidance
feat(flow): update move guidance per frame [M-083]
```

## Change budget

A PR should contain at most 400 changed, non-generated lines. Lockfiles and generated
files do not count toward the limit, but must be identified in the PR. If a change
cannot be split below 400 lines, explain why in the GitHub Issue before implementation.

## Dependencies and stacked PRs

Base a PR on `main` when all dependencies are merged. When a dependency is still open:

- Base the new branch on the dependency branch.
- State the base branch and blocking PR in both the Issue and PR.
- Keep each stacked PR independently buildable and reviewable.
- Change the base to `main` after the dependency merges.

## Required proof

Every PR records:

- A normal-path check and its result.
- A failure-path check, recovery check, or explicit not-applicable reason.
- Evidence such as a safe log excerpt, fixture, screenshot, or recording.
- Risk, expected failure mode, and rollback procedure.

Never put images, object labels, prompts, tokens, embeddings, or PII in logs or PR
evidence.

## Merge policy

- Use squash merge.
- Do not use merge commits or rebase merge for normal PRs.
- The squash commit subject must equal the PR title and include `[M-###]`.
- Do not merge a stacked PR before all of its dependency PRs.
- Do not bypass required checks or merge disabled tests.
