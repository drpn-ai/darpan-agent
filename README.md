# darpan-agent

Embedded Claude agent for Darpan (DAR-BE-159). Optional component: core darpan runs without it.

- `AgentClientFactory` builds the Anthropic client once per JVM. Key order: env `ANTHROPIC_API_KEY`, then property `darpan.agent.anthropicApiKey`. Base URL: `darpan.agent.anthropicBaseUrl` (default `https://api.anthropic.com`), checked by `OutboundHttpPolicy` against `api.anthropic.com`.
- `ModelGateway` is the seam the agent loop calls; `AnthropicModelGateway` streams and returns the accumulated message.
- `AgentAvailability.state(ec)` is `AgentSwitch.state` plus `AGENT_UNAVAILABLE` when no client can be built.
- `lib/` holds only the SDK and its non-framework dependencies (Moqui shares one classloader across components; no jackson, framework or darpan jars here).

## Configuration

Nothing about the agent's behaviour is set with `-D` properties; the only properties are the Anthropic key and base URL above.

- Edited in the admin app: **Settings** (agent areas Router, Investigator, Author, Writers, Gateway: turns per hour, the global daily dollar cap, writer budgets, retries and backoff, gateway timeouts, thresholds) and the **Agent** pages (role models, model prices, prompts with their last 10 versions, reply, notice and tool texts, per-tenant daily cap overrides). Super admin only; every write is audited. CLI: `darpan-admin settings` and `darpan-admin agent config` (`agent prompt set`, `agent model set`, `agent price set`, `agent text set`, `agent tenant-cap set`, `agent reset`).
- Defaults ship in `settings-catalog.json`, `AgentConfigDefaults`, `AgentTextDefaults`, the prompt files and `tools.json`; a reset deletes the edit. Config rows are read as a 30 s snapshot; a failed load keeps the last good one.
- Fail-safes: a role whose model has no price never sends (`AGENT_REFUSED`); an unpriced response is charged at the dearest price; a model change re-runs the preflight on the next turn.
- The paid eval (`-PagentEval...` in `build.gradle`) ignores local edit rows for the two cap keys, so a local edit cannot lift its budget.
