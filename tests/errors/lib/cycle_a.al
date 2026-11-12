// Part of tests/errors/import_cycle.al: imports the file that imports it.
import "cycle_b.al";

fn from_a() { }
