# ALang for Visual Studio Code

Language support for [ALang](https://github.com/adamw7/alang), the agent-first, heap-free language. Everything
that needs the language itself asks the `alang` toolchain, so the editor reports exactly what `alang check`
does.

## Features

- **Highlighting** for `.al` files, and for ```` ```alang ```` blocks in Markdown. Habits from other languages
  that ALang rejects (`&&`, `||`, `!x`, `x++`) are marked as they are typed.
- **Diagnostics** from `alang check` when a file is opened or saved, in imported files too, with the hint
  in the message and the code linked to its entry in [docs/ERRORS.md](https://github.com/adamw7/alang/blob/main/docs/ERRORS.md).
- **Quick fixes** for every diagnostic that carries a `fix` (`&&` → `and`, a missing `;`, `mut` or type, …),
  **Explain** for every code, and *Source Action → Apply every ALang fix* (`source.fixAll.alang`, also on save
  with `"editor.codeActionsOnSave": {"source.fixAll.alang": "explicit"}`).
- **Formatting** with `alang fmt` (*Format Document*, or `"editor.formatOnSave": true`).
- **Hovers, completion and signature help** for all builtins, generated from the toolchain's own table.
- **Code lenses**: *Run* (and *Run with input file…* for a `fn main(input: str[N])`) above `fn main`;
  *Run test* and *Debug* above each `test`; *Call…* and *Fuzz* above each `tool fn`. They run in the
  *ALang* terminal.
- **Commands** (*ALang:* in the Command Palette): Check File, Run, Run Tests, Fuzz, Call a Tool, Show Tool
  Definitions, Show Worst-Case Stack per Function, Compile to C, Explain a Diagnostic Code.
- **Outline** of functions, structs, enums, constants and tests; **snippets** for `fn main`, `tool fn`,
  `test`, `while … limit`, `match` on `Option`/`Result` and more.

## Requirements

The `alang` command: install it with

```sh
curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh
```

or unpack a [release](https://github.com/adamw7/alang/releases/latest). The extension finds it on the
`PATH`, or uses `bin/alang` when the workspace is the ALang repository itself.

## Settings

| Setting | Default | |
|---------|---------|-|
| `alang.path` | `""` | The `alang` command, e.g. `/opt/alang/bin/alang`. Empty: `bin/alang` in the ALang repository, else `alang` on the `PATH`. |
| `alang.checkOnOpen` | `true` | Check a file when it is opened. |
| `alang.checkOnSave` | `true` | Re-check every open ALang file when one is saved. |
| `alang.codeLens` | `true` | Show the Run / Run test / Debug / Call / Fuzz lenses. |

Diagnostics follow the file on disk: `alang check` resolves imports from it, so they update on save.

## Developing

```sh
cd editors/vscode
npm install
npm test                 # grammar, unit tests, and the extension against ../../bin/alang
npm run builtins         # regenerate src/builtins.json after builtins change in Builtin.java
npm run package          # alang-0.1.0.vsix; install with: code --install-extension alang-0.1.0.vsix
```

Or open this folder in VS Code and press F5 to start an Extension Development Host.
