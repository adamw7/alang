// fn main(input: Order): the standard input is JSON for the type, read before main starts.
struct Line {
    sku: str[8],
    qty: u16,
}

struct Order {
    customer: str[16],
    lines: list[Line; 8],
    express: bool = false,
}

fn main(input: Order) {
    var total: i64 = 0;
    for l in input.lines {
        total += i64(l.qty);
    }
    print(input.customer, " ordered ", total, " item(s)", if input.express then ", express" else "");
    for text in ["1e2", "1.0", "100e-2", "1.5", "-0", "12345678901234567890123", "1e2147483648", "0.1e1", "5E-0"] {
        let n: Option<i64> = from_json(text);
        print(text, " -> ", n);
    }
}
