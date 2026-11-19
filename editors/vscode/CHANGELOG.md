# Changelog

## Unreleased

- *Apply every ALang fix* now runs from `"editor.codeActionsOnSave": {"source.fixAll.alang": "explicit"}`:
  a request for exactly that kind got no action before.
- An end-to-end suite (`npm run test:e2e`) runs the extension inside a real VS Code.

## 0.1.0

- First release: highlighting (also in Markdown ```` ```alang ```` blocks), snippets, `alang check` diagnostics
  on open and save with quick fixes and links to docs/ERRORS.md, formatting with `alang fmt`, hovers,
  completion and signature help for every builtin, an outline, and Run / Run test / Debug / Call / Fuzz code
  lenses.
