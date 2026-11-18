# darpan-agent

Embedded Claude agent for Darpan (DAR-BE-159). Optional component: core darpan runs without it.

- `AgentClientFactory` builds the Anthropic client once per JVM. Key order: env `ANTHROPIC_API_KEY`, then property `darpan.agent.anthropicApiKey`. Base URL: `darpan.agent.anthropicBaseUrl` (default `https://api.anthropic.com`), checked by `OutboundHttpPolicy` against `api.anthropic.com`.
- `ModelGateway` is the seam the agent loop calls; `AnthropicModelGateway` streams and returns the accumulated message.
- `AgentAvailability.state(ec)` is `AgentSwitch.state` plus `AGENT_UNAVAILABLE` when no client can be built.
- `lib/` holds only the SDK and its non-framework dependencies (Moqui shares one classloader across components; no jackson, framework or darpan jars here).

## Configuration

Agent behaviour is not set with `-D` properties. What stays a property: the Anthropic key and base URL above, the deployment switch `darpan.agent.enabled` / `DARPAN_AGENT_ENABLED`, and `darpan.agent.fallbacks` (`false` stops server-side fallbacks). The catalog keys (limits, caps, timeouts) still honour a `-D` value, below a settings row (DAR-BE-304 precedence: row, then property, then default).

- Edited in the admin app: **Settings** (agent areas Router, Investigator, Author, Writers, Gateway: turns per hour, the global daily dollar cap, writer budgets, retries and backoff, gateway timeouts, thresholds) and the **Agent** pages (role models, model prices, prompts with their last 10 versions, reply, notice and tool texts, per-tenant daily cap overrides). Super admin only; every write is audited. CLI: `darpan-admin settings` and `darpan-admin agent config` (`agent prompt set`, `agent model set`, `agent price set`, `agent text set`, `agent tenant-cap set`, `agent reset`).
- Defaults ship in `settings-catalog.json`, `AgentConfigDefaults`, `AgentTextDefaults`, the prompt files and `tools.json`; a reset deletes the edit. Config rows are read as a 30 s snapshot; a failed load keeps the last good one.
- Fail-safes: a role whose model has no price never sends (`AGENT_REFUSED`); an unpriced response is charged at the dearest price; a model change re-runs the preflight on the next turn.
- The paid eval (`-PagentEval...` in `build.gradle`) runs on the shipped defaults whatever local rows say: it ignores the two cap-key rows and the AgentConfig rows (models, prices, prompts, texts, tenant caps; only `-Ddarpan.agent.authorModel` overrides a model), so a local edit cannot lift its budget or under-price its ledger and estimate.
