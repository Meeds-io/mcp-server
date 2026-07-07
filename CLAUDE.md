# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Meeds/eXo add-on that implements the **Model Context Protocol (MCP) server** with OAuth 2.1 protected-resource semantics. It exposes eXo platform operations (spaces, activities, users, categories, space templates) as MCP tools that an AI agent (EVA) calls **as the authenticated user**, so platform ACLs apply to every call. It ships as a Spring Boot WAR deployed inside the eXo Tomcat under context path `/mcp-server`, with the MCP endpoint at `/mcp-server/mcp`.

## Build & test

```bash
mvn clean install                              # full build (all 4 modules)
mvn install -pl mcp-server-tools               # single module (add -am to build deps too)
mvn test -pl mcp-server-tools                  # tests for one module
mvn test -pl mcp-server-tools -Dtest=SpaceMcpToolTest              # single test class
mvn test -pl mcp-server-tools -Dtest=SpaceMcpToolTest#createSpace  # single test method
```

- **JDK 21.** CI (`.github/workflows/prbuild.yml`) builds with profiles `exo-release,coverage`. Depends on eXo Nexus (`repository.exoplatform.org`) for `addons-parent-pom` and `io.meeds.*` artifacts.
- Tests run against a **real eXo kernel + Spring context** (`IntegrationTestBase` in mcp-server-tools, `McpServiceIntegrationTestSupport` in mcp-server-service) via `KernelExtension` + `@SpringBootTest(MOCK)` — they are integration tests, not fast unit tests. `mcp-server-tools` has a coverage gate of `0.66`.
- `mcp-server-tools/pom.xml` sets the javac **`-parameters`** flag. This is required — see tool argument binding below. Do not remove it.

## Module layout

| Module | Packaging | Role |
|--------|-----------|------|
| `mcp-server-service` | jar | Framework: tool registry, security/OAuth, approval flow, MCP callback wiring. Contains **no** business tools. |
| `mcp-server-tools`   | jar | The actual tools (`*McpTool` classes) + their JSON tool definitions. Add new tools here. |
| `mcp-server-webapp`  | war | Spring Boot app entrypoint, Spring `@Configuration` beans, OAuth consent i18n. |
| `mcp-server-packaging` | zip | Assembly bundling the WAR + runtime libs into an eXo add-on zip. |

## How a tool becomes an MCP tool (the core mechanism)

Adding a tool requires **two** things that must stay in sync, or the tool is silently dropped:

1. **A Java method** on a `@Service` class implementing `io.meeds.mcp.server.plugin.McpToolPlugin` (in `mcp-server-tools`, e.g. `SpaceMcpTool`, `ActivityMcpTool`, `UserMcpTool`, `CategoryMcpTool`, `SpaceTemplateMcpTool`). Every public method becomes a candidate tool. The method name is converted **camelCase → snake_case** for the tool name (`createSpace` → `create_space`).

2. **A matching entry in `ai-tool-definitions.json`** (`mcp-server-tools/src/main/resources/`, root `{"tools":[…]}`), keyed by the snake_case `name`. `McpServerToolService.retrieveToolDefinitions()` loads all `ai-tool-definitions.json` resources from the portal classloader; `McpToolCallbackProviderService.toToolCallback()` only builds a `ToolCallback` for a method **if a definition with the matching name exists** (`getToolDefinitionByMethodName` returns null otherwise → method skipped). No annotation like `@Tool`/`@McpTool` is used.

**Argument binding:** JSON `input_schema` properties are snake_case; `MethodToolCallbackWrapper` converts incoming args snake_case → camelCase before invoking the method, and binds them to method parameters **by name** — which only works because of the `-parameters` compiler flag. Without it, args bind to null.

**Tool definitions are cached in eXo Settings**, not read fresh from JSON each boot. `McpServerToolService` persists a base64 blob under key `AI_AGENT_TOOL_DEFINITIONS_v13` (context `AI_AGENT`). Saved (admin-edited) definitions win over the JSON on the classpath. To force re-import of the JSON (e.g. after editing schemas/titles), set `meeds.mcp.tools.forceReimport=true` — **or bump the `v13` suffix** in `TOOLS_KEY` when the shape changes. Live edits go through `updateToolDefinition()` which re-registers the tool on the running MCP server and broadcasts `ai-agent-tool-updated`.

## Security & auth model

- Spring Security filter chain (`McpServerSecurityConfiguration`): `/.well-known/**` is public (OAuth protected-resource metadata); `/mcp`, `/mcp/**` require one of the scope authorities; everything else is `denyAll`.
- **Scopes** (`McpToolUtils`): `mcp.tools.read` (`TOOL_READ_SCOPE`), `mcp.tools.write` (`TOOL_WRITE_SCOPE`), `mcp.tools.writeWithApproval` (`TOOL_WRITE_APPROVE_SCOPE`). A tool's `readOnlyHint` annotation (falling back to `!requireApproval`) decides read vs write; `isAllowedTool` checks the caller's granted scope authorities against it.
- **Two OAuth clients** (configured in `mcp-server.properties`): `mcp-internal` (client_credentials, used when EVA/the platform calls the server internally — it then impersonates the user via the `userName`/`contextId` request headers, see `McpToolUtils.getCurrentUserName`) and `mcp-introspection-client` (opaque-token introspection against `/auth-server/oauth2/introspect`). Bearer tokens are validated by `McpServerOauthOpaqueTokenIntrospector`.
- **Acting as the user:** every tool call sets `ConversationState` to the resolved user identity (`MethodToolCallbackWrapper.call`), so `McpToolPlugin.getCurrentUserName()` / `getCurrentUserAclIdentity()` return the real end user and eXo `SpaceService`/ACL permission checks are enforced normally. Tools should always resolve the current user via these helpers and check permissions (`canManageSpace`, `canViewSpace`, etc.) rather than trusting inputs.

## Write-tool approval flow

Tools with `"require_approval": true` in the JSON and a caller holding `mcp.tools.writeWithApproval` go through `McpToolApprovalService` before executing: the server pushes an approval request to the user over the **CometD channel `/eXo/Application/AiAgent`** and blocks (`waitForAnswer`, default timeout `meeds.mcp.tool.userApproval.timeout=120000` ms) until the user's browser answers `answer:<id>:<true|false>`. Denied → `UserToolDeniedException`; timeout → `UserToolTimeoutException`. Every state transition (`APPROVAL_REQUEST`, `TOOL_EXECUTION_START/FINISHED/DENIED/ERROR`, …) is traced via `traceToolExecution` (CometD message + `ai-agent-tool-execution` listener event) so the AI UI can render live progress.

## Conventions

- **Spring for services** (`@Service`/`@Autowired`), including for eXo kernel singletons (`SpaceService`, `IdentityManager`, `UserACL`…) resolved through the Meeds Spring↔Kernel bridge (`PortalApplicationContextInitializer`). Portal wiring (e.g. space-template site config) uses eXo Kernel `configuration.xml` `external-component-plugins`.
- Tool methods return plain model records/POJOs (`SpaceModel`, `ActivityModel`, `UserModel`…) that MCP serializes to JSON; conversion helpers live in `tool/util/*ToolUtils`.
- Throw `IllegalArgumentException` / `IllegalStateException` / `ObjectNotFoundException` / `IllegalAccessException` with **LLM-directed messages** (the wrapper rethrows these verbatim; other exceptions get wrapped in a generic explanation). Messages often instruct the LLM what to tell the user or which tool to call next.
- i18n: edit only `_en` bundles (`locale/portlet/OAuthConsent_en.properties`); Crowdin syncs the rest.
- License header (LGPL v3, `This file is part of the Meeds project`) is required on every source file.
