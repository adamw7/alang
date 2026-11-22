// from_json(text) reads JSON as a value of the type of the Option it is stored in; Option.None for any other text.
struct Item {
    name: str[16],
    qty: i64,
    price: f64 = 1.5,
    tags: set[str[8]; 4] = [],
}

enum Status {
    Open,
    Closed(str[16]),
}

struct Order {
    id: u32,
    items: list[Item; 4],
    status: Status,
    notes: map[str[8], i32; 4],
    pairs: map[i64, bool; 2],
    point: (f32, i8),
}

fn show(text: str[512]) {
    let o: Option<Order> = from_json(text);
    match o {
        Option.Some(order) => { print(order); }
        Option.None => { print("not an order: ", text); }
    }
}

fn main() {
    show(r"""
        {"id": 7, "items": [{"name": "pen", "qty": 2}, {"name": "caf\u00e9", "qty": 1e1, "price": 0.1,
          "tags": ["b", "a"]}], "status": {"Closed": ["late"]}, "notes": {"x": -3, "y": 2},
          "pairs": [[5, true], [-1, false]], "point": [0.3, -128]}
        """);
    show(r"""{"id": 1, "items": [], "status": "Open", "notes": {}, "pairs": [], "point": [1e-50, 0]}""");
    show(r"""{"id": -1, "items": [], "status": "Open", "notes": {}, "pairs": [], "point": [0, 0]}""");
    show(r"""{"id": 1, "items": [], "status": "Open", "notes": {}, "pairs": [], "point": [0, 0], "extra": 1}""");
    show(r"""{"id": 1, "items": [], "status": "Closed", "notes": {}, "pairs": [], "point": [0, 0]}""");
    show(r"""{"id": 1.5, "items": [], "status": "Open", "notes": {}, "pairs": [], "point": [0, 0]}""");
    show(r"""{"id": 1, "items": [], "status": "Open", "notes": {"a": 1, "a": 2}, "pairs": [], "point": [0, 0]}""");
    let n: Option<i64> = from_json(" -12 ");
    let big: Option<u64> = from_json("18446744073709551615");
    let over: Option<u8> = from_json("256");
    let f: Option<f64> = from_json("0.30000000000000004");
    let tiny: Option<f64> = from_json("-4.9e-324");
    let huge: Option<f32> = from_json("3.5e38");
    let words: Option<list[str[4]; 3]> = from_json(r"""["a\n", "\ud83d\ude00", "\/"]""");
    let bad: Option<str[4]> = from_json(r"""["\ud800"]""");
    print(n, " ", big, " ", over, " ", f, " ", tiny, " ", huge);
    print(words, " ", bad);
}
