# Prompt archive

This directory preserves prior versions of agent prompt bodies for history and
rollback reference. It is a **source-tree convention only** — nothing here is
read at runtime, and the directory is not required to exist in the built jar.

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
`prompt_registry.json`. Archiving the old copy (step 1) is not machine-checked
— it is a convention for humans (and future git-blame archaeology), not a CI
gate.
