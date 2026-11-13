# Examples

Complete ALang programs. Each one has a `fn main` and `test` blocks, and most expose `tool fn`s an
agent can call with JSON. CI type-checks every example, runs its tests, compares its output with
[`tests/examples/`](../tests/examples), and checks that the C backend prints the same bytes.

| Program | What it shows |
|---------|---------------|
| [hello.al](hello.al) | The smallest program |
| [fizzbuzz.al](fizzbuzz.al) | Loops, conditions, building text in a fixed-capacity `str` |
| [binary_search.al](binary_search.al) | A bounded `while` loop, `requires`/`ensures` contracts |
| [shapes.al](shapes.al) | Structs, enums with payloads, exhaustive `match`, value semantics |
| [text_tools.al](text_tools.al) | Text tools on the standard library: `words`, `to_upper`, `sort`, `contains`, `join` |
| [inventory.al](inventory.al) | State passed as a value and changed through `mut` parameters; results as enums |
| [rpn_calculator.al](rpn_calculator.al) | A tokenizer and an evaluation stack; errors as enum values instead of exceptions |
| [maze_bfs.al](maze_bfs.al) | Breadth-first search with a fixed-capacity queue in place of recursion |
| [primes.al](primes.al) | A sieve over `[bool; N]`, factoring, `ensures` clauses that call functions, loop limits worked out from the math |
| [game_of_life.al](game_of_life.al) | Two-dimensional arrays, edges that wrap around with `%`, boards as values that `step` never changes |
| [sudoku.al](sudoku.al) | Backtracking on an explicit stack, `u16` bit sets, a step budget that gives up with a value instead of a trap |
| [base64.al](base64.al) | Shifts and masks with the required parentheses; raw bytes returned as `list[u8; N]` |
| [task_scheduler.al](task_scheduler.al) | Topological sort, a tool that takes a list of structs, cycles reported as values |
| [stats.al](stats.al) | `f64` statistics and least squares, `sqrt`, float comparisons with a tolerance |
| [vending_machine.al](vending_machine.al) | A state machine: enum events as JSON input, `mut` state, an `ensures` on a `mut` parameter |
| [sorting.al](sorting.al) | Merge sort and quicksort without recursion, sortedness as a contract, a seeded pseudo-random generator |
| [helpers.al](helpers.al) | Helpers the standard library leaves out, to import or copy: parsing into an enum instead of trapping, pseudo-random numbers, a list of entries as a map; their parameters take any capacity (`list[Entry; _]`, `str[_]`) |
| [tally.al](tally.al) | `import "helpers.al";` instead of copying, and a `fn main(input: str[N])` that reads the standard input ([tally.in](tally.in)) |

## Try them

```sh
bin/alang run examples/sudoku.al
bin/alang test examples/sorting.al
bin/alang tools examples/vending_machine.al
bin/alang check examples/game_of_life.al --memory
bin/alang run examples/tally.al < examples/tally.in

bin/alang call examples/primes.al factorize '{"n": 600851475143}'
# {"ok": true, "result": [71, 839, 1471, 6857], "stdout": "", "steps": 4920}
bin/alang call examples/vending_machine.al simulate '{"events": [{"Coin": [200]}, {"Select": ["A2"]}, "Cancel"]}'
# {"ok": true, "result": [{"Credit": [200]}, {"Vended": ["Juice", 25]}, {"Refunded": [0]}], "stdout": "", "steps": 44}
bin/alang call examples/task_scheduler.al schedule '{"tasks": [{"name": "test", "deps": ["build"]}, {"name": "build", "deps": []}]}'
# {"ok": true, "result": {"Order": [["build", "test"]]}, "stdout": "", "steps": 63}
bin/alang call examples/stats.al fit_line '{"xs": [0, 1, 2], "ys": [1, 3, 5]}'
# {"ok": true, "result": {"Ok": [{"slope": 2.0, "intercept": 1.0, "r_squared": 1.0}]}, "stdout": "", "steps": 68}
bin/alang call examples/base64.al encode '{"data": "agents"}'
# {"ok": true, "result": "YWdlbnRz", "stdout": "", "steps": 38}
```

To add an example, put `NAME.al` here and its exact `bin/alang run` output in `tests/examples/NAME.out`;
if its main reads the standard input, put that input in `NAME.in` here. It must check without a single
warning, and its tests must pass.
