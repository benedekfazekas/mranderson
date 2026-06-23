<!-- Keep it short and conversational. Explain why, not a file-by-file list. -->

## What and why

<!-- 1-3 sentences. What does this change and why is it needed?
     Reference related issues with "Fixes #NN" or "Closes #NN". -->

## Checklist

- [ ] Tests added or updated (for engine changes, prefer an end-to-end inlining test)
- [ ] `lein test` passes
- [ ] `lein with-profile +eastwood eastwood` is clean (it lints tests too)
- [ ] `CHANGELOG.md` updated under `Unreleased` (for user-facing changes)
- [ ] Ran `lein test :benchmark` if the rewriting engine changed
