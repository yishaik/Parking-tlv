# Parking-tlv local QA assessment

Date: 2026-11-20

Indicative maturity: **2/5 (early prototype)**. This is an MCP server with parking rules and checked-in GIS snapshots; the README describes Android and Android Auto as future work. A browser interface is not present. The score is a qualitative local assessment, not a standardized project rating.

## Evidence and limitations

- `package.json` defines Vitest and TypeScript checks; `test/rules.test.ts` covers basic rules and geometry, and `test/zones.real.test.ts` checks two real addresses and an offshore point.
- Dependencies were absent. `npm ci --offline --ignore-scripts --no-audit --no-fund` failed with `ENOTCACHED` for `zod-to-json-schema`; the normal install did not complete. Tests, typecheck, and MCP runtime verification could not be performed.
- The snapshots contain 9 zones, 94 garages, and 146 night lots, fetched on 2026-11-20 around 12:56 UTC. No live sync was performed.
- `README.md` identifies an unverified visitor rate and payment-window end; the configured unknown rate produces a null cost when paid time exists.
- `src/rules.ts` uses the host timezone for local clock calculations, which can yield different results outside Israel. Its daily free allowance is initialized once for an entire stay, including stays crossing midnight. These are code-review risks, not runtime-verified failures.
- `.github/workflows/` contains GIS update workflows but no automated test or typecheck workflow.
- No browser flow exists in this checkout to verify. Creating a box or updating `/boxes` was not performed: no local box integration is present, and external/CRM updates are outside the authorized scope.
