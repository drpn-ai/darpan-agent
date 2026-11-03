# darpan-agent

Embedded Claude agent for Darpan (DAR-BE-159). Optional component: core darpan runs without it.

- `AgentClientFactory` builds the Anthropic client once per JVM. Key order: env `ANTHROPIC_API_KEY`, then property `darpan.agent.anthropicApiKey`. Base URL: `darpan.agent.anthropicBaseUrl` (default `https://api.anthropic.com`), checked by `OutboundHttpPolicy` against `api.anthropic.com`.
- `ModelGateway` is the seam the agent loop calls; `AnthropicModelGateway` streams and returns the accumulated message.
- `AgentAvailability.state(ec)` is `AgentSwitch.state` plus `AGENT_UNAVAILABLE` when no client can be built.
- `lib/` holds only the SDK and its non-framework dependencies (Moqui shares one classloader across components; no jackson, framework or darpan jars here).
