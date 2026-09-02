# Changelog

`CHANGELOG.md` at the repo root, hand-authored in Keep-a-Changelog format,
mirroring the web repo's `CHANGELOG.md`. Bullets are user-facing prose,
never generated from commit messages.

## When to update

After every ticket whose work lands on main — the same session that closes
the ticket:

1. Add one bullet to `[Unreleased]` under the fitting group: `Added` for new
   features, `Changed` for behavior changes, `Fixed` for bug fixes,
   `Removed` otherwise.
2. Word it as feature-area prose with a bold area lead ("**Wallets** — …")
   and end with the ticket refs (`#15`, `#16`), like the web repo's
   changelog. One bullet per ticket; a feature spanning several tickets may
   share one bullet carrying all their refs.
3. Skip what a user would never notice: an internal refactor with no
   behavior change gets no bullet of its own (its ticket's bullet covers it
   if one exists).

Completion criterion: every ticket closed since the last changelog edit is
represented by exactly one bullet in the right group, and no bullet
describes work that never landed.

## The first release is M1

The app's first release is the M1 milestone (spec #13: auth, shell,
wallets, categories, transactions, dashboard). When M1 completes:

1. Rename `[Unreleased]` to `[v1.0.0] — YYYY-MM-DD`, keep its bullets, and
   start a fresh empty `[Unreleased]` below.
2. Add the release link at the bottom
   (`[v1.0.0]: https://github.com/<owner>/<repo>/releases/tag/v1.0.0`),
   matching the web repo, where every changelog entry is a `v*` tag recorded
   on GitHub Releases.
