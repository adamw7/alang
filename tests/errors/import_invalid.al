// expect: E0702
import "lib\\shapes.al";
import "/usr/lib/shapes.al";
import "lib/shapes";
import "lib/names.al";
import "./lib/../lib/names.al";

fn main() { }
