# Agent-captured memories merge only restatements, and link what the conversation read

- **Status:** Accepted
- **Revisions:** v1 2026-11-18 (initial); v2 2026-11-18 (the UserPromptSubmit hook captures explicit cues)
- **Deciders:** Sriharsha Chintalapani
- **Guard:** `AgentMemoryPlanTest`, `AgentMemoryDuplicatesTest`, `SessionAssetsTest`, `RememberToolIT`,
  `PromptMemoryCuesTest`, `PromptAssetLookupTest`, `AutomaticMemoryCaptureIT`
- **Related:** docs/plans/2026-11-18-agent-harness-implementation-plan.md (section 2.4),
  ADR:2026-11-05-oss-memories-are-approved-without-reconciliation,
  ADR:2026-10-09-data-consumers-create-context-memories,
  ADR:2026-11-18-a-memory-shared-with-a-persona-reaches-its-holders-at-read-time

## Context
The harness Memory settings (capture, link assets, deduplicate, review, visibility) were stored and
unused. An agent could only write a memory through `create_entity`, which forces `RememberRequest`
and knows nothing of the settings. The `remember` tool applies them. Two choices were not obvious:
what "deduplicate" compares, and which assets a memory links to.

## Decision
- A memory the `remember` tool writes has `sourceType = AgentCapture`. It is created through the
  same repository path as any memory the caller creates, so ownership, viewable-reference checks and
  the deployment's approval rule (this ADR's Related records) apply unchanged. The harness decides
  only who may read it: Private is the author, Persona shares it with the caller's applied persona,
  Organization is `Public`. Review `AuthorOnly` forces Private whatever the visibility says. A caller
  with no applied persona who asks for Persona visibility gets Private, and the result says so.
- Deduplicate means one thing: a live (Unprocessed, Draft or Approved) memory on the same primary
  asset, which the caller may read, whose answer is the same words once case, punctuation and
  spacing are ignored. The tool returns that memory as `deduplicatedInto` and writes nothing. A fact
  that differs is a new memory, however similar. `SemanticMemoryDuplicateFinder` is not used: it
  compares what documents state, needs the search index and an AI judge, and holds a differing value
  for review under rules written for documents. A restatement of an agent's own fact needs none of
  that, and an index outage must not stop a user from recording a fact. A memory with no primary
  asset is never merged.
- The memory links to the assets named in `about` (the first is the primary) and then to the assets
  the conversation read, newest first, up to 20. The tool keeps a bounded record of what each
  conversation read: 50 assets per conversation, 10,000 conversations, 2 hours idle. It counts
  `get_entity_details`, `get_concept_context` and the concepts and tables of `finish_answer`.
  Search results are not counted, because they list candidates the agent may never have used. A
  conversation is the same key `DeliveryLedger` uses: user, client, persona and session id. Names
  the caller cannot view are dropped, and the result lists the ones the agent named.
- A fact is at most 2,000 characters.

## Amendment v2: the hook captures explicit cues
A user had to tell the agent to store a fact, because only a model's `remember` call wrote one.
Claude Code and Codex send the user's prompt to `harness_hook` at UserPromptSubmit, so the server
can save a stated fact with no model choice. Claude Desktop sends no hook and still depends on
the model calling `remember`.
- `PromptMemoryCues` reads the prompt with fixed patterns, with no model: `remember that`,
  `note that`, `remember:`, `note:`, `from now on`, `going forward`, `for future reference`,
  `always use X for Y`, `never use X for Y`, `use X instead of Y`, and `X is deprecated, use Y`, at
  the start of a sentence or after `please`. A sentence with a `?`, text in a fenced code block, a
  prompt over 4,000 characters, a fact under 12 or over 2,000 characters, and a fact that matches a
  credential, key, email address or card-number pattern are never captured. The first fact of a
  prompt is kept. The codebase has no secret or PII detector to reuse, so the patterns are
  local to this class and err toward not capturing.
- The hook saves through `RememberTool` (`captureFromPrompt`), so the harness settings, review,
  visibility, persona share, dedup and session-asset linking apply unchanged. The conversation is
  the transport's `DeliveryLedger.Caller` key, which the model's reads were recorded under; the
  hook body's `session_id` is not used.
- The source type stays `AgentCapture`, with the description "Recorded automatically from the
  user's prompt". `ConversationExtraction` is the chat-turn extractor's ground-truth type and is
  read by that path; using it would let a regex match pass as extracted ground truth. A new enum
  value would add schema for a label the description carries.
- Tables and metrics named in the fact (a backticked word, or a word with an underscore or dot)
  are resolved as the caller through search: a fully qualified name, or a name that matches
  exactly one asset. An ambiguous name is not linked.
- A fact saved is reported in the hook's context for that turn, ahead of the compiled items and
  inside the host's cap, as a note that tells the agent to say so. A bounded record of recent facts
  per user (10,000 entries, 10 minutes) returns the same note for a retried hook without a
  second memory, including for a fact with no anchor, which the dedup never merges.
- The hook's failure to save never fails the hook.

## Consequences
Restating a fact never creates a second memory, and nothing decides whether two differing facts
conflict: that is left to memory review as for any memory. The record is in memory, per server, so a
conversation served by two servers links fewer assets, and a restart forgets the conversation. Revisit
the comparison when agents capture enough near-duplicates to be a review burden: the next step is the
vector search the finder already uses, over agent captures too.
