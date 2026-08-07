# Prompt archive

This directory preserves prior versions of agent prompt bodies.

> ⚠️ **This archive is READ AT RUNTIME.** It used to be a source-tree convention
> only; it is not any more. `PromptArchive` loads every file here and
> `AgentDefinitionBootstrap` uses the resulting hash set to decide whether a
> stored prompt in `agent_definition` is a *stale default* (safe to overwrite
> with the current bundled prompt) or a *genuine operator edit* (must be
> preserved). Maven copies `src/main/resources` wholesale, so these files ship
> in the fat jar under `BOOT-INF/classes/prompts/archive/`; `PromptArchiveTest`
> pins that they stay reachable.
>
> **Consequence of skipping step 1 below:** the old prompt is no longer
> recognised as one we shipped, so it is treated as an operator edit and the new
> bundled prompt **will not propagate** to that agent. The failure is loud (a
> WARN containing `diverges from bundled`), not silent — but it still needs a
> manual agent-definition reset to clear.

## Convention

Whenever a prompt file in `java-server/src/main/resources/prompts/*.md` gets a
**version bump** (i.e. you are about to edit an agent's prompt body and change
its `<!-- agent-meta ... version: X.Y.Z -->` header):

1. **Before editing**, copy the OLD file, unchanged, to:

   ```
   archive/<agent>/<old-version>.md
   ```

   Example: before bumping `strigoi-spin.md` from `1.0.0` to `1.1.0`, copy the
   current file to `archive/strigoi-spin/1.0.0.md`.

2. Edit the live file in place: change the body and bump the `version:` field
   in its `<!-- agent-meta -->` header.

3. Update `../prompt_registry.json`: bump `version` to match the new header,
   and recompute `body_hash` as `"p-" + sha256(body).substring(0, 12)` (the
   same derivation `PromptHashes.hash(...)` uses in
   `de.visterion.dracul.agent`), where `body` is the file content *after* the
   `<!-- agent-meta ... -->` header block.

`PromptRegistryTest` enforces steps 2 and 3: it fails the build if any live
prompt file's header version or body hash no longer matches
`prompt_registry.json`. Archiving the old copy (step 1) is still not
machine-checked — the build cannot know which versions once shipped — but it is
no longer merely cosmetic: see the warning above.
