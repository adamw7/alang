// to_json(v) writes any value as JSON, in the forms tool results and from_json use.
struct Item {
    name: str[16],
    qty: i64,
    price: f64,
    tags: list[str[8]; 4],
}

enum Status {
    Open,
    Closed(str[16]),
    Moved(i64, i64),
}

struct Order {
    id: u64,
    items: list[Item; 4],
    status: Status,
    paid: bool,
    note: Option<str[32]>,
}

fn main() {
    let o: Order = Order(id: 18446744073709551615, items: [
        Item(name: "tea", qty: 2, price: 3.5, tags: ["hot"]),
        Item(name: "café \"x\"", qty: -1, price: 0.1, tags: []),
    ], status: Status.Moved(1, -2), paid: true, note: Option.None);
    print(to_json(o));
    let text: str[512] = to_json(o);
    let back: Option<Order> = from_json(text);
    print(back == Option.Some(o));
    var m: map[str[8], i64; 4] = [];
    m["b"] = 2;
    m["a"] = 1;
    var n: map[i64, bool; 4] = [];
    n[3] = true;
    let st: set[i64; 4] = [3, 1, 2];
    print(to_json(m), " ", to_json(n), " ", to_json(st));
    print(to_json(Status.Open), " ", to_json(Status.Closed("tab\there")), " ", to_json((1, "x", false)));
    let none: list[i64; 2] = [];
    print(to_json(none), " ", to_json("line\nnext\u{1}"), " ", to_json(u8(255)), " ", to_json(i8(-128)));
    let fs: [f64; 16] = [0.1, 1.0, 100.0, 1e21, 1e20, 123456789012345680000.0, 1.5e-7, 1e-7, 0.0000001234, -0.0, 5e-324,
        1.7976931348623157e308, 2.0 / 3.0, 9007199254740993.0, 0.3, 2.2250738585072014e-308];
    for f in fs {
        print(to_json(f));
    }
    let gs: [f32; 6] = [f32(0.1), f32(16777216.0), f32(3.4028235e38), f32(1e-45), f32(1.0) / f32(3.0), f32(1e21)];
    for g in gs {
        print(to_json(g));
    }
    var s: str[64] = "json: ";
    append(mut s, to_json([1, 2]));
    print(s, " ", pad_left(to_json(true), 8, '.'));
    let j: str[8] = to_json(42);
    print(j);
}

test "NaN has no JSON" traps R0013 {
    let z: f64 = 0.0;
    print(to_json(z / z));
}

test "too long for its str" traps R0004 {
    let j: str[2] = to_json(123);
    print(j);
}
