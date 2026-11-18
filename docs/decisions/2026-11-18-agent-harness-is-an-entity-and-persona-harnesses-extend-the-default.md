# Agent harness is an entity, and persona harnesses extend the company default

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial)
- **Deciders:** Sriharsha Chintalapani
- **Guard:** `HarnessResolverTest`, `AgentHarnessResourceIT`, `AgentHarnessMigrationIT`, `AgentHarnessAuthorizationIT`
- **Related:** docs/plans/2026-11-18-agent-harness-implementation-plan.md, ADR:2026-10-14-persona-ai-context-status-reads-the-cache-and-pages-like-the-persona-list

## Context
Persona AI context stored each persona's rules and prompt on the persona (`persona.contextDefinition`)
and edited them in place: every save changed what agents received. The Agent Harness page adds a
company-wide default that applies to every user, persona harnesses layered on it, delivery per hook
event, guardrails, and memory settings, all edited as a draft and released with Publish. Entity
versioning cannot hold a draft: `EntityRepository` consolidates one user's edits within a session into
one version, so a draft stored on the entity would merge into the publish.

## Decision
- `agentHarness` is an entity with its own table. Its JSON is the **published** harness, and Publish
  is its only write, so its versions are its publish history. Drafts live in the unversioned
  `agent_harness_draft` table, written whole with `If-Match` on a revision number.
- Exactly one harness has kind `Default` (`CompanyDefault`); a `Persona` harness refers to one persona
  by id. A persona harness **extends** the default: the compiler merges the published default and
  persona into one `PersonaContextDefinition`, default rules first, rule names prefixed `default/` and
  `persona/`. Guardrail levels and event budgets resolve to the stricter of the two at compile time,
  so a later change to the default never leaves a persona looser.
- The persona is chosen as before (`X-OpenMetadata-Persona`, the user's default persona, the system
  default persona). If it has no enabled, published harness, the caller gets the default alone.
- The 2.1.0 migration moves every `contextDefinition` into a published persona harness, then removes
  the field from persona rows and persona versions. The default is seeded with no rules and no
  instructions, so compiled context and MCP search scope do not change on upgrade.
- The persona AI context endpoints are removed with no compatibility layer; the API and UI ship in
  the same release. The rule of the record this supersedes carries to
  `GET /v1/agentHarness/personas`: it pages with the persona list's cursor, reads only the cache, and
  is admin-only.
- Every harness write, publish, preview, and plugin download is admin-only (`authorizeAdmin`). The
  entity has no `owners`: seeded bot policies grant `EditAll` on all resources, and the
  organization owner rules would let any user claim an unowned harness.
- The generated plugin carries no harness content. Instructions, persona prompts, skills, and
  guardrail decisions are served per caller by `POST /v1/agentHarness/compile` and the `get_context`
  MCP tool.

## Consequences
Publishing a harness never requires reinstalling a plugin, and an admin can edit without affecting
agents until Publish. Downgrading below 2.1.0 is not supported once the migration has removed
`contextDefinition`. Reading a harness's draft and published state takes two queries. Revisit if
OpenMetadata gains a general draft mechanism for entities.
