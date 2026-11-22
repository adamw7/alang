// Functions are values: in variables, fields, lists, constants and results, called by any expression that gives one.
struct Op {
    name: str[8],
    run: fn(i64, i64) -> i64,
}

fn add(a: i64, b: i64) -> i64 {
    return a + b;
}

fn mul(a: i64, b: i64) -> i64 {
    return a * b;
}

fn sub(a: i64, b: i64) -> i64 {
    return a - b;
}

fn inc(x: i64) -> i64 {
    return x + 1;
}

fn double(x: i64) -> i64 {
    return x * 2;
}

fn is_odd(x: i64) -> bool {
    return x % 2 != 0;
}

const OPS: [fn(i64, i64) -> i64; 3] = [add, mul, sub];

fn pick(name: str[8]) -> fn(i64) -> i64 {
    return if name == "inc" then inc else double;
}

fn apply_all(fs: list[fn(i64) -> i64; 4], x: i64) -> i64 {
    var v: i64 = x;
    for f in fs {
        v = f(v);
    }
    return v;
}

fn twice<T>(f: fn(T) -> T, x: T) -> T {
    return f(f(x));
}

fn total(xs: list[i64; _]) -> i64 {
    return sum(xs);
}

fn main() {
    let f: fn(i64) -> i64 = inc;
    print(f(41), " ", pick("inc")(1), " ", pick("double")(21));
    let ops: list[Op; 4] = [Op(name: "add", run: add), Op(name: "mul", run: mul)];
    for op in ops {
        print(op.name, " ", op.run(6, 7));
    }
    let first: Op = ops[0];
    print(first.run(1, 2), " ", OPS[2](10, 3), " ", ops[1].run(3, 3));
    print(apply_all([inc, double, inc], 5));
    var g: fn(i64) -> i64 = double;
    print(twice(g, 3), " ", twice(inc, 3));
    g = inc;
    print(g == inc, " ", g == double, " ", g);
    let keep: fn(i64) -> bool = is_odd;
    print(count_if([1, 2, 3, 5], keep), " ", any([2, 4], keep));
    let t: fn(list[i64; 3]) -> i64 = total;
    print(t([1, 2, 3]));
    print(first);
}
