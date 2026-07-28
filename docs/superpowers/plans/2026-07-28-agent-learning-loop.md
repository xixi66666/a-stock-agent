# Agent Learning Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a default-off, offline-runnable Spring AI learning path covering Chat Memory, Advisors, deterministic Embeddings, an in-memory Vector Store, RAG, and bounded MCP-style tools without changing the existing stock research APIs.

**Architecture:** Keep one Spring Boot module and add an isolated `agent.learning` package. A new learning endpoint invokes a facade that assembles memory, trace, and RAG advisors; it uses `ChatClient` when a model is configured and a deterministic evidence response otherwise. The vector store and MCP adapter remain in-process and bounded, with official Spring AI vector/MCP dependencies only for the interfaces and optional transport.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, Caffeine, Spring AI `Document`/`EmbeddingModel`/`ChatMemory`/Advisor APIs, JUnit 5, AssertJ, MockMvc.

---

## File Map

Create the following focused modules:

- `src/main/java/com/astock/agent/agent/learning/LearningAgentProperties.java`: typed feature flags and bounds.
- `src/main/java/com/astock/agent/agent/learning/memory/ConversationMemoryService.java`: validated conversation access and message-window operations.
- `src/main/java/com/astock/agent/agent/learning/memory/LearningChatMemoryConfiguration.java`: Spring AI in-memory `ChatMemory` bean.
- `src/main/java/com/astock/agent/agent/learning/embedding/DeterministicEmbeddingModel.java`: offline fixed-dimension `EmbeddingModel`.
- `src/main/java/com/astock/agent/agent/learning/vector/InMemoryResearchVectorStore.java`: document, vector, metadata, and cosine search storage.
- `src/main/java/com/astock/agent/agent/learning/vector/VectorSearchResult.java`: immutable search result with score and metadata.
- `src/main/java/com/astock/agent/agent/learning/rag/ResearchKnowledgeIndexer.java`: converts normalized research data into documents and indexes them.
- `src/main/java/com/astock/agent/agent/learning/rag/ResearchRetriever.java`: question embedding, filtered Top-K search, and source-preserving context.
- `src/main/java/com/astock/agent/agent/learning/rag/RagContext.java`: immutable retrieved context and missing-state representation.
- `src/main/java/com/astock/agent/agent/learning/advisor/TraceAdvisor.java`: call advisor that records ordered stages and timing.
- `src/main/java/com/astock/agent/agent/learning/advisor/RagAdvisor.java`: call advisor that injects retrieved context into the request.
- `src/main/java/com/astock/agent/agent/learning/advisor/LearningAdvisorFactory.java`: assembles advisors in deterministic order.
- `src/main/java/com/astock/agent/agent/learning/mcp/McpToolDescriptor.java`: bounded tool metadata.
- `src/main/java/com/astock/agent/agent/learning/mcp/McpToolExecution.java`: structured execution result.
- `src/main/java/com/astock/agent/agent/learning/mcp/ResearchMcpToolProvider.java`: stock research and RAG tools with input validation.
- `src/main/java/com/astock/agent/agent/learning/LearningAgentResponse.java`: API response containing answer and trace.
- `src/main/java/com/astock/agent/agent/learning/LearningAgentFacade.java`: offline/ChatClient execution coordinator.
- `src/main/java/com/astock/agent/agent/learning/LearningAgentConfiguration.java`: conditional Bean wiring.
- `src/main/java/com/astock/agent/web/LearningAgentController.java`: new `/api/agent/learning` endpoint.

Modify:

- `pom.xml`: add `spring-ai-vector-store` and optional MCP server starter managed by the existing BOM.
- `src/main/resources/application.yml`: add default-off `app.agent.learning` properties.
- `config/application-local.yml.example`: document learning switches without secrets.
- `README.md`: add the offline learning workflow and endpoint example.

Create focused tests under `src/test/java/com/astock/agent/agent/learning` and `src/test/java/com/astock/agent/web`.

## Task 1: Configuration And Dependencies

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `config/application-local.yml.example`
- Create: `src/main/java/com/astock/agent/agent/learning/LearningAgentProperties.java`
- Test: `src/test/java/com/astock/agent/agent/learning/LearningAgentPropertiesTest.java`

- [ ] **Step 1: Write the failing properties test**

Assert that a default `LearningAgentProperties` instance is disabled, uses 24 max history messages, Top-K 4, and embedding dimension 128; assert invalid values are clamped or rejected with `IllegalArgumentException`.

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LearningAgentPropertiesTest' test
```

Expected: compilation failure because the properties record does not exist.

- [ ] **Step 3: Add dependencies and minimal properties implementation**

Add BOM-managed `org.springframework.ai:spring-ai-vector-store` and `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` as optional learning dependencies. Add `app.agent.learning.enabled=false`, feature switches, `max-history-messages=24`, `top-k=4`, and `embedding-dimension=128` to `application.yml`. Bind a record with `@ConfigurationProperties("app.agent.learning")` and clamp history to 2..100, Top-K to 1..20, and dimensions to 8..1024.

- [ ] **Step 4: Run the focused test to verify GREEN**

Run the same command. Expected: all properties assertions pass without starting an external service.

- [ ] **Step 5: Commit the configuration slice**

```powershell
git add pom.xml src/main/resources/application.yml config/application-local.yml.example src/main/java/com/astock/agent/agent/learning/LearningAgentProperties.java src/test/java/com/astock/agent/agent/learning/LearningAgentPropertiesTest.java
git commit -m "feat: configure optional agent learning modules"
```

## Task 2: Chat Memory

**Files:**
- Create: `src/main/java/com/astock/agent/agent/learning/memory/ConversationMemoryService.java`
- Create: `src/main/java/com/astock/agent/agent/learning/memory/LearningChatMemoryConfiguration.java`
- Test: `src/test/java/com/astock/agent/agent/learning/memory/ConversationMemoryServiceTest.java`

- [ ] **Step 1: Write the failing memory tests**

Use real `MessageWindowChatMemory` and `InMemoryChatMemoryRepository`. Test that two conversation IDs do not share messages, a conversation is capped at the configured message window, blank IDs are rejected, and `clear` removes all messages.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ConversationMemoryServiceTest' test
```

Expected: missing `ConversationMemoryService` compilation failure.

- [ ] **Step 3: Implement the memory service**

Build `MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(properties.maxHistoryMessages()).build()`. Expose `addUserMessage`, `addAssistantMessage`, `messages`, and `clear`, each validating a nonblank conversation ID and copying returned lists.

- [ ] **Step 4: Run GREEN and the existing agent tests**

Run the focused test and `AgentStatusServiceTest`. Expected: both pass and no model key is required.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/astock/agent/agent/learning/memory src/test/java/com/astock/agent/agent/learning/memory
git commit -m "feat: add bounded in-memory chat memory"
```

## Task 3: Deterministic Embedding And Vector Store

**Files:**
- Create: `src/main/java/com/astock/agent/agent/learning/embedding/DeterministicEmbeddingModel.java`
- Create: `src/main/java/com/astock/agent/agent/learning/vector/InMemoryResearchVectorStore.java`
- Create: `src/main/java/com/astock/agent/agent/learning/vector/VectorSearchResult.java`
- Test: `src/test/java/com/astock/agent/agent/learning/embedding/DeterministicEmbeddingModelTest.java`
- Test: `src/test/java/com/astock/agent/agent/learning/vector/InMemoryResearchVectorStoreTest.java`

- [ ] **Step 1: Write failing embedding tests**

Assert that `embed("盈利增长")` is deterministic, has the configured dimension, is finite, and that empty input produces a zero vector rather than a network request.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=DeterministicEmbeddingModelTest' test
```

Expected: missing embedding implementation compilation failure.

- [ ] **Step 3: Implement the deterministic model**

Implement `EmbeddingModel.call(EmbeddingRequest)` and `embed(Document)`. Normalize text by Unicode code point, hash each token into positive and negative buckets, normalize the resulting float array, and return Spring AI `Embedding`/`EmbeddingResponse` objects. Do not use randomness, clocks, or HTTP.

- [ ] **Step 4: Write failing vector-store tests**

Add documents for “盈利增长” and “技术指标”，search for “盈利”，assert the first result is the financial document, assert Top-K and `securityCode` metadata filtering, and assert `clear` returns no results.

- [ ] **Step 5: Run RED for the vector store**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=InMemoryResearchVectorStoreTest' test
```

Expected: missing vector-store implementation compilation failure.

- [ ] **Step 6: Implement the vector store**

Store immutable entries containing a Spring AI `Document` and its copied float vector. Expose `add(List<Document>)`, `search(String query, String securityCode, int topK)`, and `clear()`. Embed the query with the deterministic model, skip zero-norm entries, calculate cosine similarity, filter metadata before sorting, and return `VectorSearchResult` with document ID, score, text, and metadata.

- [ ] **Step 7: Run GREEN**

Run both focused tests. Expected: deterministic embedding and ordered metadata-filtered search pass.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/astock/agent/agent/learning/embedding src/main/java/com/astock/agent/agent/learning/vector src/test/java/com/astock/agent/agent/learning/embedding src/test/java/com/astock/agent/agent/learning/vector
git commit -m "feat: add deterministic embeddings and in-memory vector search"
```

## Task 4: RAG Indexing And Retrieval

**Files:**
- Create: `src/main/java/com/astock/agent/agent/learning/rag/RagContext.java`
- Create: `src/main/java/com/astock/agent/agent/learning/rag/ResearchKnowledgeIndexer.java`
- Create: `src/main/java/com/astock/agent/agent/learning/rag/ResearchRetriever.java`
- Test: `src/test/java/com/astock/agent/agent/learning/rag/ResearchKnowledgeIndexerTest.java`
- Test: `src/test/java/com/astock/agent/agent/learning/rag/ResearchRetrieverTest.java`

- [ ] **Step 1: Write failing indexing tests**

Build a fixture `StockResearchSnapshot` with a quote, technical card, and one unavailable section. Assert indexing creates documents only for available evidence, includes `securityCode`, `section`, provider, source URL, provider timestamp, and fetched timestamp metadata, and never fabricates unavailable data.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchKnowledgeIndexerTest' test
```

Expected: missing RAG classes compilation failure.

- [ ] **Step 3: Implement the indexer**

Convert each available `DataSection` into a bounded text document with a section label and factual values. Copy provenance into metadata and use stable IDs derived from security code, section, and source URL. Keep unavailable sections out of the index and return a count plus skipped section names.

- [ ] **Step 4: Write failing retrieval tests**

Index two sections for `600519`, retrieve with a question, assert Top-K is honored, returned context includes text, score, provider, URL, and fetch time, and an unknown security code returns an explicit empty/missing context.

- [ ] **Step 5: Run RED and implement the retriever**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchRetrieverTest' test
```

Implement `retrieve(question, securityCode, topK)` using the vector store. Return an immutable `RagContext` with `documents`, `formattedPromptContext`, and `missingReason`; never replace empty results with guessed facts.

- [ ] **Step 6: Run GREEN and commit**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchKnowledgeIndexerTest,ResearchRetrieverTest' test
git add src/main/java/com/astock/agent/agent/learning/rag src/test/java/com/astock/agent/agent/learning/rag
git commit -m "feat: index provenance-aware research for offline rag"
```

## Task 5: Advisors And Learning Facade

**Files:**
- Create: `src/main/java/com/astock/agent/agent/learning/advisor/TraceAdvisor.java`
- Create: `src/main/java/com/astock/agent/agent/learning/advisor/RagAdvisor.java`
- Create: `src/main/java/com/astock/agent/agent/learning/advisor/LearningAdvisorFactory.java`
- Create: `src/main/java/com/astock/agent/agent/learning/LearningAgentResponse.java`
- Create: `src/main/java/com/astock/agent/agent/learning/LearningAgentFacade.java`
- Create: `src/main/java/com/astock/agent/agent/learning/LearningAgentConfiguration.java`
- Test: `src/test/java/com/astock/agent/agent/learning/advisor/LearningAdvisorFactoryTest.java`
- Test: `src/test/java/com/astock/agent/agent/learning/LearningAgentFacadeTest.java`

- [ ] **Step 1: Write failing advisor tests**

Create a fake `CallAdvisorChain` and `ChatClientRequest`. Assert the factory order is `trace`, `memory`, `rag`; `RagAdvisor` puts a `RagContext` in the request context and appends only retrieved evidence; `TraceAdvisor` records both success and failure stages.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LearningAdvisorFactoryTest' test
```

Expected: missing advisor classes compilation failure.

- [ ] **Step 3: Implement advisors and factory**

Implement Spring AI `CallAdvisor` methods using `ChatClientRequest.mutate()` and `ChatClientResponse` context. Use a request context key for the conversation ID and trace collector. Build the list from feature flags; omit disabled advisors without changing order of enabled advisors.

- [ ] **Step 4: Write failing facade tests**

Test the no-model path with a real memory service, deterministic retriever, and bounded MCP provider. Assert the response contains a nonempty answer, `modelUsed=offline-deterministic`, memory count, retrieved source metadata, tool execution trace, advisor trace, and the disclaimer. Test a second message in the same conversation sees the first message; test invalid input is rejected.

- [ ] **Step 5: Run RED and implement the facade**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LearningAgentFacadeTest' test
```

Implement `chat(code, message, conversationId)`. Normalize and validate `SecurityId`, index the current snapshot when RAG is enabled, run bounded tools, and choose the ChatClient path only when `AgentStatusService` is `READY`; otherwise return a deterministic evidence summary. Keep all failures section-local and include trace states.

- [ ] **Step 6: Run GREEN and commit**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LearningAdvisorFactoryTest,LearningAgentFacadeTest' test
git add src/main/java/com/astock/agent/agent/learning/advisor src/main/java/com/astock/agent/agent/learning/LearningAgentResponse.java src/main/java/com/astock/agent/agent/learning/LearningAgentFacade.java src/main/java/com/astock/agent/agent/learning/LearningAgentConfiguration.java src/test/java/com/astock/agent/agent/learning/advisor src/test/java/com/astock/agent/agent/learning/LearningAgentFacadeTest.java
git commit -m "feat: add advisor-driven offline learning agent"
```

## Task 6: Bounded MCP Tool Provider

**Files:**
- Create: `src/main/java/com/astock/agent/agent/learning/mcp/McpToolDescriptor.java`
- Create: `src/main/java/com/astock/agent/agent/learning/mcp/McpToolExecution.java`
- Create: `src/main/java/com/astock/agent/agent/learning/mcp/ResearchMcpToolProvider.java`
- Test: `src/test/java/com/astock/agent/agent/learning/mcp/ResearchMcpToolProviderTest.java`

- [ ] **Step 1: Write failing MCP contract tests**

Assert descriptors exist for `search_stock`, `get_research_snapshot`, `get_technical_analysis`, and `search_knowledge`; valid six-digit codes execute through injected services; URLs, shell-like strings, path separators, oversized messages, and unknown tool names return structured failures without invoking a provider.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchMcpToolProviderTest' test
```

Expected: missing descriptor/provider classes compilation failure.

- [ ] **Step 3: Implement the local provider and optional MCP wiring**

Use immutable descriptors with JSON-like input schemas and a dispatcher that returns `McpToolExecution(success, data, errorCode)`. Delegate only to `ResearchAggregationService`, `TechnicalAnalysisService`, and `ResearchRetriever`. Register the provider as a normal Spring Bean when learning is enabled; keep the web MCP server starter disabled unless an explicit property enables it.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ResearchMcpToolProviderTest' test
git add src/main/java/com/astock/agent/agent/learning/mcp src/test/java/com/astock/agent/agent/learning/mcp
git commit -m "feat: add bounded research mcp tool provider"
```

## Task 7: Learning REST API

**Files:**
- Create: `src/main/java/com/astock/agent/web/LearningAgentController.java`
- Test: `src/test/java/com/astock/agent/web/LearningAgentControllerTest.java`

- [ ] **Step 1: Write failing MockMvc contracts**

Assert disabled learning returns `404` or a typed disabled response without creating a model call; enabled offline learning returns `200` with `modelUsed`, trace, and disclaimer; invalid code and a message longer than 2,000 Unicode code points return `400` Problem Details.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LearningAgentControllerTest' test
```

Expected: missing controller mapping failure.

- [ ] **Step 3: Implement the controller**

Map `POST /api/agent/learning/chat` to a request record containing `code`, `message`, and `conversationId`. Validate code through `SecurityId.parse`, limit messages to 2,000 Unicode code points, require a nonblank conversation ID, and delegate all behavior to `LearningAgentFacade`.

- [ ] **Step 4: Run GREEN and full offline tests**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LearningAgentControllerTest' test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' test
```

Expected: new API tests and all existing offline tests pass; external tests remain excluded.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/astock/agent/web/LearningAgentController.java src/test/java/com/astock/agent/web/LearningAgentControllerTest.java
git commit -m "feat: expose offline agent learning endpoint"
```

## Task 8: Documentation And Final Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/data-flow.md`
- Test: existing project tests only; no network tests added.

- [ ] **Step 1: Document the learning path**

Add the feature flags, endpoint request/response, offline execution command, module map, RAG indexing behavior, MCP safety limits, and instructions for enabling a real model. State clearly that deterministic Embedding is educational and that the project disclaimer remains active.

- [ ] **Step 2: Run repository checks**

```powershell
git diff --check
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
```

Expected: zero test failures, successful package creation, no whitespace errors, and no external provider calls.

- [ ] **Step 3: Inspect behavior without credentials**

Start the packaged application without `config/application-local.yml`, call `/actuator/health`, `/api/agent/status`, and `/api/agent/learning/chat`, and verify the learning response is deterministic, source-aware, and marked with the disclaimer.

- [ ] **Step 4: Review secrets and working tree**

Run the repository secret audit and `git status --short`; only intentional source, test, documentation, and plan/spec commits may remain. Do not add `out/`, target reports, local configuration, API keys, or tokens.

- [ ] **Step 5: Commit documentation and final corrections**

```powershell
git add README.md docs/architecture/data-flow.md
git commit -m "docs: document agent learning workflow"
```
