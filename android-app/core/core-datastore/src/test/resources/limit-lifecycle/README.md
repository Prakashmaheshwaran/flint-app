# Literal limit-row migration inputs

These inputs are literal wire strings, not generated with the decoder/writer
under test. Test loaders remove only the file-ending newline. Each v5 migration
test must preserve the original input in its archive, migrate once, then perform
a second pass without changing the result or accounting/occurrence identity.

- v2: current merged codec at `5c696e0`; Time `package~minutes~tier`, Open
  `package~credits~tier`. Separate zero-budget rows are valid historical settings.
- v3: prepared from C2 R-18b's saved-zone writer (read-only source inspection,
  branch `v1/c/time-limit-trusted-day` on 2026-10-30); Time appends saved zone,
  Open stays unchanged. **Provisional until the R-18b merge is verified.**
- v4: the approved #180 contract leaves both rows identical to v3 and adds
  separate migration/catalog children. **Provisional until the #180 merge is
  verified.** These files alone are not complete v4 preference snapshots.

The v5 store tests must compose each version's real required child set, including
the trusted Time ledger, Open ledger, pending/wait and catalog where applicable.
No migration is wired or claimed by adding these input files. The production
v5 writer appends enabled after Time's saved zone and after Open's tier.
