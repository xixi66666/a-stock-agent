# Local Model Provider Configuration Design

## Goal

Create `config/application-local.yml` for one manually selected OpenAI-compatible chat provider. Support OpenAI, DeepSeek, and Xiaomi MiMo without adding dependencies or changing application code.

## Configuration Shape

The file contains one active `spring.ai.openai` block and two fully commented alternative blocks. OpenAI is active by default. To switch providers, the developer comments the current block, uncomments the target block, fills in its API key, and restarts the application.

All three providers use the existing `spring-ai-starter-model-openai` dependency and these Spring AI properties:

- `spring.ai.model.chat: openai`
- `spring.ai.openai.api-key`
- `spring.ai.openai.base-url`
- `spring.ai.openai.chat.completions-path` when a provider base URL already contains `/v1`
- `spring.ai.openai.chat.options.model`
- `spring.ai.openai.chat.options.temperature`

## Provider Templates

The OpenAI template uses `https://api.openai.com` and leaves the model identifier as an account-specific placeholder. The DeepSeek template uses `https://api.deepseek.com` and `deepseek-chat`. The Xiaomi MiMo template uses its documented OpenAI-compatible base URL `https://api.xiaomimimo.com/v1`, overrides the Spring AI completion path to `/chat/completions`, and uses `mimo-v2.5-pro`. This produces the documented final request URL `https://api.xiaomimimo.com/v1/chat/completions` without duplicating `/v1`.

Provider model identifiers remain editable because availability can vary by account and provider release cycle.

## Secret Handling

No real API key is written by the implementation. Each template contains a clearly named replacement value. `config/application-local.yml` remains ignored by Git, while `config/application-local.yml.example` remains a placeholder-only tracked example.

The application must not log the API key. Existing status reporting may report whether configuration is present but must not expose the configured value.

## Failure Behavior

An empty or unreplaced key means the Agent is not ready for real model requests. Market data, technical analysis, and non-Agent REST APIs remain usable. Provider authentication, quota, model availability, and compatibility failures remain visible as Agent-local failures and do not fabricate an analysis result.

## Documentation

`README.md` must document how to create `config/application-local.yml`, keep exactly one provider block active, fill the selected key and model, switch among OpenAI, DeepSeek, and Xiaomi MiMo, restart the application, and keep real credentials out of tracked files and logs.

## Verification

Verification will confirm that:

1. The local file is ignored by Git.
2. Spring Boot can load the YAML without making an external model request.
3. The existing offline test suite still passes.
4. No real key-like value appears in the diff or tracked files.
