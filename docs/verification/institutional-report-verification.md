# Institutional Report Verification

> 历史验证记录：下文测试数量、环境限制及 `/api/agent/analyze` 契约属于当时实现，不代表当前版本或当前环境。该接口已不在现有 Controller 中；当前默认入口为 `/api/finrobot/tasks`，旧同步入口为 `/api/finrobot/research`。现行运行与验证步骤见 [README](../../README.md) 和 [官方 FinRobot 接入](../finrobot-official.md)。2026-09-15 文档核对未重新运行这些历史验证。

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
