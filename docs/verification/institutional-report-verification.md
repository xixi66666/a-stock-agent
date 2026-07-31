# Institutional Report Verification

## Offline evidence

- `mvnw.cmd -Dmaven.repo.local=.m2/repository clean test`: 78 tests passed.
- `mvnw.cmd -Dmaven.repo.local=.m2/repository -DskipTests package`: executable Spring Boot JAR built.
- Focused report tests cover deterministic direction, missing dimensions,
  trend/flow conflicts, evidence bounds, fallback disclaimer, validator
  rejection, and the `/api/agent/analyze` contract.
- `node --check` passes for `static/js/app.js` and `static/js/views.js`.

## Environment-limited checks

- `scripts/verify-data.cmd` is an external live-data check. In this run all
  three securities were unavailable because the public providers could not
  return core quote/K-line data; this is not an offline test failure.
- `npm run test:ui` was not runnable because the workspace has no installed
  `playwright` command or `node_modules`. Install the pinned package with
  `npm.cmd install --cache .npm-cache` before running the four viewport checks.

## Security and output rules

The report never serializes `internalScore`, confidence percentages, API keys,
provider response bodies, direct trade instructions or guaranteed returns. The
disclaimer is always fixed to `仅供学习研究，不构成投资建议`.
