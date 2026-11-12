package alang.diag;

/**
 * Every diagnostic and runtime trap has a stable code. Agents can look codes up with
 * {@code alang explain <CODE>}. Codes are never reused for a different meaning.
 */
public enum Code {
    // ---- syntax ----
    E0001("syntax error",
        "The source does not match the ALang grammar (grammar/ALang.g4). The hint usually names the fix.",
        "let x: i32 = 1 + 2 * 3;   // ok\nif (a && b) { }          // error: use `and`, parentheses are optional"),

    // ---- names ----
    E0101("unknown name",
        "A variable or constant is used but was never declared in scope.",
        "let y: i32 = x + 1;  // error if no `x` is in scope"),
    E0102("unknown function",
        "A call names a function, struct or builtin that does not exist.",
        "let n: i64 = length(xs);  // error: the builtin is `len`"),
    E0103("unknown type",
        "A type name is not a primitive type and no struct or enum with that name exists.",
        "let p: Pointt = Point(x: 1, y: 2);  // typo"),
    E0104("duplicate declaration",
        "Two top-level items (functions, structs, enums, constants) share a name, or a struct/enum declares the same field/variant twice.",
        "fn f() { }\nfn f() { }  // error"),
    E0105("name already in use",
        "ALang has no shadowing: a local name cannot reuse the name of another local in the same function, a parameter, a constant or a type.",
        "let x: i32 = 1;\nif c { let x: i32 = 2; }  // error: pick a new name"),
    E0106("unknown field",
        "The struct type has no field with this name, or a struct literal names a field that does not exist.",
        "struct P { x: i32 }\nlet v: i32 = p.y;  // error"),
    E0107("unknown enum variant",
        "The enum has no variant with this name.",
        "enum Color { Red, Green }\nlet c: Color = Color.Blue;  // error"),
    E0108("reserved name",
        "The name is reserved for a builtin function and cannot be used for a declaration.",
        "fn print() { }  // error: `print` is a builtin"),

    // ---- types ----
    E0201("type mismatch",
        "A value of one type is used where another type is required. ALang never converts implicitly; convert explicitly with a conversion call such as i64(x) or f64(n).",
        "let a: i32 = 1;\nlet b: i64 = a;       // error\nlet c: i64 = i64(a);  // ok"),
    E0202("literal out of range",
        "An integer literal does not fit the type it is used as.",
        "let b: u8 = 300;  // error: u8 holds 0..255"),
    E0203("invalid operand",
        "The operator is not defined for the operand type(s), e.g. `+` on bool, `<` on structs, `%` on floats, or `-` on unsigned integers.",
        "let s: bool = true + false;  // error"),
    E0204("wrong number of arguments",
        "A call passes more or fewer arguments than the function, variant or builtin accepts.",
        "fn f(a: i32) { }\nf(1, 2);  // error"),
    E0205("cannot assign to immutable value",
        "Only `var` locals and `mut` parameters can be modified. `let` locals, loop variables, match bindings, plain parameters and constants are read-only.",
        "let x: i32 = 1;\nx = 2;  // error: declare with `var x: i32 = 1;`"),
    E0206("invalid assignment target",
        "The left side of an assignment must be a variable, field or element of a variable.",
        "Color.Red = Color.Green;  // error"),
    E0207("mut argument mismatch",
        "A `mut` parameter must receive `mut place` at the call site, with exactly the parameter's type; a plain parameter must not be marked `mut`.",
        "fn inc(x: mut i32) { x += 1; }\ninc(mut n);  // ok\ninc(n);      // error: write `mut n`"),
    E0208("aliased mut argument",
        "A variable passed as `mut` may not appear anywhere else in the same call. This guarantees the callee has exclusive access.",
        "append(mut s, s);            // error: copy first: let t: str[16] = s; append(mut s, t);\nswap(mut xs[i], mut xs[j]);  // error: i and j may be equal"),
    E0209("not a constant expression",
        "Constants, array sizes, capacities and repeat counts must be computable at compile time: literals, other constants, operators, conversions and literals of structs, enums and arrays. Function calls are not allowed.",
        "const N: i32 = f();  // error"),
    E0210("invalid size",
        "Array lengths and str/list capacities must be integer constants between 1 and 1048576.",
        "let a: [i32; 0] = [];  // error"),
    E0211("condition must be bool",
        "Conditions of `if`, `while`, `assert`, `requires`, `ensures` and operands of and/or/not must have type bool. There is no truthiness.",
        "if n { }       // error\nif n != 0 { }  // ok"),
    E0212("cannot index",
        "Only arrays, lists and strings can be indexed, and the index must be an integer.",
        "let p: Point = ...;\nlet v: i32 = p[0];  // error"),
    E0213("bad struct literal",
        "A struct literal must name every field exactly once: Point(x: 1, y: 2).",
        "let p: Point = Point(x: 1);  // error: missing field `y`"),
    E0214("invalid conversion",
        "Conversions are written as a call to a primitive type, e.g. i32(x), f64(n), u8(c). Numeric types convert to each other; bool converts to integers. Nothing converts to bool: compare instead (x != 0).",
        "let b: bool = bool(1);  // error: write 1 != 0"),
    E0215("mut call not allowed here",
        "A call that passes a `mut` argument must be a statement on its own, or the whole right-hand side of a let/var/assignment. This keeps evaluation order obvious.",
        "print(pop(mut xs));          // error\nlet top: i32 = pop(mut xs);  // ok\nprint(top);"),
    E0216("type cannot be inferred",
        "The expression needs a type annotation from context, e.g. an empty array literal `[]` must be assigned to a typed list.",
        "print([]);  // error"),

    // ---- control flow ----
    E0301("missing return",
        "A function with a return type must return a value on every path. Loops are not considered to always return.",
        "fn sign(x: i32) -> i32 { if x > 0 { return 1; } }  // error: add a final return"),
    E0302("break/continue outside loop",
        "`break` and `continue` can only be used inside `while` or `for` loops.",
        "fn f() { break; }  // error"),
    E0303("bad return",
        "A function without a return type cannot return a value, and a function with one must return a value.",
        "fn f() { return 1; }  // error"),
    E0305("statement has no effect",
        "Only calls can be used as expression statements.",
        "x + 1;  // error: did you mean x += 1;"),
    E0306("non-exhaustive match",
        "A match must cover every enum variant (or both bool values), or end with a `_ => { }` arm. Matches on integers and strings always need `_`.",
        "match c { Color.Red => { } }  // error: Color.Green not covered"),
    E0307("unreachable or duplicate match arm",
        "A pattern is listed twice, or appears after `_`.",
        "match n { _ => { } 1 => { } }  // error"),
    E0308("pattern mismatch",
        "The pattern does not fit the type of the matched value, or binds the wrong number of payload values.",
        "match shape { Shape.Circle => { } }  // error: write Shape.Circle(r) or Shape.Circle(_)"),

    // ---- memory, termination ----
    E0401("recursion is not allowed",
        "ALang forbids recursion (direct or indirect) so that every program has a stack bound known at compile time and always terminates. Rewrite the algorithm with a loop and an explicit, fixed-capacity work list.",
        "fn fact(n: u64) -> u64 { return n * fact(n - 1); }  // error"),
    E0402("stack bound exceeded",
        "The worst-case stack usage computed by the checker exceeds the memory budget: 8 MiB (8388608 bytes) unless --max-stack sets another, 0 for none. The figure for an entry point (main, a tool or a test) counts the parameters, locals, return values and aggregate temporaries of its deepest chain of calls; `alang check --memory` lists it per function. The budget keeps a checked program within the memory of the interpreter and of the default C stack. Reduce capacities, or pass large values as `mut` parameters instead of copying them.",
        "fn main() {\n    let grid: [[i64; 1024]; 1024] = [[0; 1024]; 1024];  // 8 MiB, plus 8 MiB to build it: error\n}"),
    E0403("recursive type",
        "A struct or enum contains itself (directly or through other types). Without pointers this would have infinite size. Use indexes into a fixed-capacity list instead.",
        "struct Node { next: Node }  // error"),
    E0404("nested too deeply",
        "A program may nest at most 128 levels deep. Each bracket and each operator chained in an expression (a field access `.f` and an index `[i]` included) counts one level, and a function, constant or type adds the levels of every function, constant and type it uses. The limit is the same on every machine: the toolchain recurses over the program, and without it the machine's stack size would decide whether a deeply nested program can be checked or run. Split deep expressions with intermediate `let` bindings and shorten long chains of declarations that use each other.",
        "let x: i64 = 1 + 1 + 1 + ... + 1;  // 200 chained `+`: error\nlet a: i64 = 1 + 1 + ... + 1;       // 100 of them\nlet x: i64 = a + 1 + ... + 1;       // and 100 more: ok"),
    E0405("constant too large",
        "Constants are evaluated while the program is checked and stay in memory for the whole run. Together they may hold at most the memory budget: 8 MiB (8388608 bytes) unless --max-stack sets another, 0 for none. Each constant counts its own size, the values its initializer builds on the way, and the constants checked before it. The capacities, array lengths and repeat counts in types and `[v; n]` are computed while checking too, and what they build counts the same way. Make the constant smaller, or build the data at run time in a function.",
        "const TABLE: [[i64; 1024]; 1024] = [[0; 1024]; 1024];  // 8 MiB, plus 8 MiB to build it: error"),

    // ---- contracts ----
    E0501("misplaced `result`",
        "`result` refers to the return value and can only be used inside `ensures` of a function that returns a value.",
        "fn f(x: i32) -> i32 ensures result > x { return x + 1; }"),

    // ---- entry points and tools ----
    E0601("invalid tool signature",
        "Tool functions are called with JSON arguments, so they cannot take `mut` parameters.",
        "tool fn f(x: mut i32) { }  // error"),
    E0602("invalid main",
        "`fn main()` returns nothing, and takes no parameters or one: a read-only `str[N]` that receives all of the "
            + "program's standard input, e.g. `alang run prog.al < data.txt`. Input longer than N bytes traps (R0004).",
        "fn main() { print(\"hello\"); }\nfn main(input: str[4096]) { print(len(input)); }"),

    // ---- imports ----
    E0701("imported file not found",
        "An `import` names a file that cannot be read: it does not exist, it is not readable, or it is not UTF-8 text. "
            + "The path is relative to the file that has the import, not to the current directory.",
        "// in examples/report.al:\nimport \"helpers.al\";  // reads examples/helpers.al"),
    E0702("invalid import",
        "An import names a relative path to a .al file, with / between directories, at most once per file: no "
            + "absolute paths, no backslashes. Imports come before every other declaration of a file.",
        "import \"lib/text.al\";   // ok\nimport \"C:\\\\lib\\\\text.al\";  // error: write a relative path with /"),
    E0703("import cycle",
        "Files import each other, directly or through other files. Imports form a tree (a file may be imported "
            + "by several others); move what the files share into a file that both import.",
        "// a.al: import \"b.al\";\n// b.al: import \"a.al\";  // error"),

    // ---- warnings ----
    W0301("unreachable code",
        "The statement can never run because every path before it returns, breaks, continues or panics.",
        "return 1;\nprint(\"never\");  // warning"),
    W0302("unused result",
        "A call's return value is discarded. If this is intended, it is fine; otherwise store it.",
        "compute(x);  // warning: compute returns i32"),
    W0303("character literal printed as a number",
        "Character literals such as 'a' are u8 integers, so print() and append() write their decimal value (97). To add the character itself to a str, use push(mut s, 'a').",
        "append(mut s, 'a');  // appends \"97\"\npush(mut s, 'a');    // appends \"a\""),

    // ---- runtime traps ----
    R0001("integer overflow",
        "An arithmetic result does not fit its type. ALang never wraps silently. Use a wider type, or check before computing.",
        "let x: u8 = 200;\nlet y: u8 = x + x;  // trap"),
    R0002("division by zero",
        "Integer division or remainder by zero.",
        "let z: i32 = 1 / n;  // trap when n == 0"),
    R0003("index out of bounds",
        "An index is negative or not smaller than the length of the array, list or string.",
        "let a: [i32; 3] = [1, 2, 3];\nlet x: i32 = a[3];  // trap"),
    R0004("capacity exceeded",
        "A push/append does not fit in the list or string capacity, or the standard input is longer than the `str[N]` "
            + "parameter of fn main. Capacities are part of the type: list[T; N], str[N].",
        "var s: str[4] = \"abc\";\nappend(mut s, \"de\");  // trap"),
    R0005("assertion failed",
        "An `assert` condition evaluated to false.",
        "assert 1 + 1 == 3;  // trap"),
    R0006("precondition failed",
        "A `requires` clause was false when the function was called. The caller is at fault.",
        "fn sqrt_int(x: i64) -> i64 requires x >= 0 { ... }"),
    R0007("postcondition failed",
        "An `ensures` clause was false when the function returned. The function is at fault.",
        "fn abs1(x: i32) -> i32 ensures result >= 0 { return x; }"),
    R0008("loop limit exceeded",
        "A `while` loop ran more iterations than its `limit`. Either the limit is too small or the loop does not make progress.",
        "while true limit 10 { }  // trap after 10 iterations"),
    R0009("conversion out of range",
        "A conversion such as u8(x), i32(f) or i64(s) received a value that the target type cannot represent: a number "
            + "out of its range, NaN, or a str that is not a decimal integer (check it first with is_int(s)).",
        "let b: u8 = u8(-1);  // trap\nlet n: i64 = i64(\"12a\");  // trap"),
    R0010("panic",
        "The program called panic(message).",
        "panic(\"unreachable state\");"),
    R0011("step budget exhausted",
        "The interpreter executed more statements than allowed by --max-steps. Raise the budget or reduce the work.",
        "alang run long.al --max-steps 1000000000"),
    R0012("invalid shift or empty pop",
        "A shift amount is negative or not smaller than the bit width, or pop() was called on an empty list/string.",
        "let x: u32 = 1 << 32;  // trap"),
    R0013("invalid argument",
        "A standard library function received an argument outside its domain: a negative exponent for pow, bounds "
            + "with lo > hi for clamp, or an empty separator or pattern for split or replace.",
        "let p: i64 = pow(2, -1);  // trap\nlet c: i64 = clamp(x, 10, 0);  // trap");

    public final String title;
    public final String explanation;
    public final String example;

    Code(String title, String explanation, String example) {
        this.title = title;
        this.explanation = explanation;
        this.example = example;
    }

    public boolean isWarning() {
        return name().startsWith("W");
    }

    public boolean isRuntime() {
        return name().startsWith("R");
    }
}
