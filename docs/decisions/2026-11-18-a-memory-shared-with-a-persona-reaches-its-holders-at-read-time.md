# A memory shared with a persona reaches the persona's holders, decided at read time

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial)
- **Deciders:** Sriharsha Chintalapani
- **Guard:** `PersonaMemoryVisibilityIT`, `ContextMemoryVisibilityTest`,
  `ContextMemorySearchVisibilityTest`, `SubjectContextTest`
- **Related:** ADR:2026-11-18-agent-harness-is-an-entity-and-persona-harnesses-extend-the-default,
  ADR:2026-11-03-context-center-listings-show-and-count-only-what-the-caller-may-read,
  docs/plans/2026-11-18-agent-harness-implementation-plan.md (§2.4, §12 decision 2)

## Context
The agent harness's memory setting offers "Persona" visibility: a memory captured under a persona
is shared with that persona's people. Sharing already named users, teams and domains by id, and
search indexes only the ids a memory names (`sharedWithIds`). Who holds a persona changes through
the persona, the user's default persona, and a team's default persona, none of which writes the
memory.

## Decision
- `persona` is a supported principal type in `contextMemory.shareConfig.sharedWith`. A share must
  name a persona that exists.
- A user holds a persona exactly when `SubjectContext.hasPersona` says so
  (`SubjectContext.heldPersonaIds`): assigned directly, inherited from one of their teams' default
  persona, or their default persona. A user who chose no default persona has the system default
  persona as their default, so a memory shared with the system default persona reaches every such
  user.
- Holding is resolved from the reader on every read: the REST checks read the user from the
  database, and search adds the held persona ids to the terms it matches against `sharedWithIds`.
  A membership change applies to the next read and needs no reindex.
- Context files share the same matcher, so a file shared with a persona follows the same rule.
- Deleting a persona leaves its grants on the memories that name it. They reach no one: the delete
  removes every assignment of the persona in the same transaction, personas are always hard
  deleted, and no new persona takes an old id.

## Consequences
Sharing with a persona costs no index field and no reindex. The plan proposed removing a deleted
persona's grants; rewriting every memory that names it would add a version to memories the
deleting admin does not own, and would leave a `Shared` memory with an empty list, which
reconciliation treats as needing a visibility repair. The cost is a stale principal in the stored
list, which a reader sees only as a share that no longer reaches anyone. Revisit if personas gain
soft delete and restore, because a restored persona would reactivate its old grants.
