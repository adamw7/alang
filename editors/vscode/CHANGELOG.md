# Changelog

## Unreleased

- Highlighting, hovers and completion know the new builtins (`unwrap_or`, `is_some`, `is_none`, `is_ok`,
  `is_err`, `assert_eq`, `strip_prefix`, `strip_suffix`, `split_once`, `repeat`, `min_by`, `max_by`, `swap`,
  `pop_front`, `union`, `intersection`, `difference`), and the `?` that takes the value out of an Option or Result.
- Highlighting, hovers and completion know the new builtins (`count_ones`, `leading_zeros`, `trailing_zeros`,
  `rotl`, `rotr`, `keys`, `values`, `variants`, `hash`, `to_base`).
- *Apply every ALang fix* now runs from `"editor.codeActionsOnSave": {"source.fixAll.alang": "explicit"}`:
  a request for exactly that kind got no action before.
- A file's diagnostics no longer depend on which check finished last: saving re-checks every open file, and
  checking main.al (which reports on the lib.al it imports) could wipe what checking lib.al had just
  reported there, such as a warning. Each file now shows every check's latest report on it, once.
- An end-to-end suite (`npm run test:e2e`) runs the extension inside a real VS Code.

## 0.1.0

- First release: highlighting (also in Markdown ```` ```alang ```` blocks), snippets, `alang check` diagnostics
  on open and save with quick fixes and links to docs/ERRORS.md, formatting with `alang fmt`, hovers,
  completion and signature help for every builtin, an outline, and Run / Run test / Debug / Call / Fuzz code
  lenses.
