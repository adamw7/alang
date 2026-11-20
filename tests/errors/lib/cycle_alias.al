// Part of tests/errors/import_cycle_alias.al: imports the main file that imports it, both with `as`.
import "../import_cycle_alias.al" as first;

fn from_other() -> i64 {
    return first.from_main();
}
