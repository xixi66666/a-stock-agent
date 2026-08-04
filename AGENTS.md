# Repository Agent Guide

## Scope

This repository is an evidence-first A-share research application. Preserve the boundary between provider adapters, normalized domain records, deterministic analysis, Spring AI tools, REST APIs, and presentation code.

## Required Workflow

- Use red-green-refactor for every behavior change or bug fix. Run the failing test before implementation and the focused passing test after it.
- Run fresh verification before claiming completion or committing a completed slice.
- Keep external tests tagged `external`; default test runs must stay offline.
- Preserve user changes in a dirty worktree and avoid unrelated refactors.

## Market Data Rules

- Prefer Tencent or mootdx-style non-blocking sources for quotes and K-lines. Use Eastmoney only for data not available from the preferred sources.
- Every Eastmoney request must pass through the shared `ProviderThrottle`. Requests are globally serialized with at least a one-second interval and jitter. Never add parallel Eastmoney loops.
- Preserve `Provenance` through parsing, fallback, caching, aggregation, API serialization, and UI rendering.
- Treat empty success, provider failure, stale data, unverified data, and unavailable data as different states. Do not fabricate replacement values.
- Validate security identity, units, timestamps, OHLC relationships, duplicate dates, ordering, and professional history length.
- Provider failures must remain section-local unless both core quote and K-line sources are unavailable.
- Public fixtures may contain market data responses only. Remove cookies, tokens, account identifiers, request traces, and private headers.

## Agent Safety

- Spring AI tools accept bounded structured inputs such as a six-digit security code. Do not expose arbitrary URL fetching, shell execution, filesystem access, or unrestricted HTTP clients as model tools.
- Deterministic calculations, validation, quality scoring, and source selection belong outside the model.
- Reports must distinguish facts, computed indicators, conflicts, missing data, and conclusions, and must cite sources.

## Secrets And Local Configuration

- Real credentials belong only in `config/application-local.yml`, which is ignored by Git.
- Never commit API keys, tokens, secrets, cookies, private endpoints, or copied local configuration.
- Keep `config/application-local.yml.example` populated with placeholders only.
- Do not log or expose query values whose names contain `key`, `token`, `secret`, or `authorization`.

## UI Rules

- Use the approved semantic tokens: canvas `#f7f7f4`, soft canvas `#fafaf7`, surface `#ffffff`, ink `#26251e`, body `#5a5852`, muted `#807d72`, hairline `#e6e5e0`, strong hairline `#cfcdc4`, purple `#7158d9`, soft purple `#eeeafd`, up red `#d83b53`, and down green `#1f8a65`.
- A-share semantics are red for up and green for down. Never rely on color alone; include text or state labels.
- The workbench is light, compact, and research-focused. Do not add gradients, dark themes, decorative blobs, oversized heroes, nested cards, or marketing copy.
- Technical indicators are independent cards. Desktop uses four columns, intermediate widths use three/two, and mobile uses one.
- Use Lucide for interface icons and ECharts for the detail chart. Icon-only buttons require an accessible label and tooltip.
- Test 1440x1000, 1024x768, 768x1024, and 390x844. Check canvas pixels, horizontal overflow, fixed-header overlap, and button text fit.

## Verification Commands

The repository-local JDK 21 is stored at `.tools/jdk-21` (currently
`D:\Code\Java_Code\a-stock-agent\.tools\jdk-21`). Before invoking Maven from
PowerShell, point the current process at that JDK so the system Java 8/11 is
not selected:

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -version
```

The version output must report Java 21. `start.ps1` performs the same local
toolchain setup for application startup.

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
.\scripts\verify-data.cmd
npm.cmd run test:ui
```

Before pushing, review `git diff --check`, run the secret audit documented in the implementation plan, and inspect generated live-data reports and UI screenshots rather than relying only on exit codes.
