// A tool that needs data its caller does not send: prices, from the host. `host fn price` has no body; the host
// answers each call. Tests bind it to a price list of their own; `alang call` and `alang serve` take --hosts:
//
//   bin/alang call examples/orders.al quote '{"lines": [{"sku": "tea", "qty": 2}]}' --hosts examples/orders.hosts.json
//
// prints the quote and every question the host was asked ("host_calls"); --replay with that report asks none.

/// The price of one item in cents, or Option.None when the shop does not sell it.
host fn price(sku: str[16]) -> Option<i64>
    ensures unwrap_or(result, 0) >= 0 and unwrap_or(result, 0) <= 100000000;

struct Line {
    /// What to buy.
    sku: str[16],
    /// How many, 1 to 1000.
    qty: i64,
}

struct Quote {
    /// The price of the lines the shop sells, in cents.
    total_cents: i64,
    /// The items it does not sell, which are left out of the total.
    unknown: list[str[16]; 16],
    /// A note for the buyer, or null.
    note: Option<str[64]>,
}

/// Prices an order: each line's price times its quantity, and the items the shop does not sell.
tool fn quote(lines: list[Line; 16]) -> Quote
    requires all(lines, sensible)
{
    var q: Quote = Quote(total_cents: 0, unknown: [], note: Option.None);
    for l in lines {
        match price(l.sku) {
            Option.Some(cents) => { q.total_cents += cents * l.qty; }
            Option.None => {
                if not contains(q.unknown, l.sku) {
                    push(mut q.unknown, l.sku);
                }
            }
        }
    }
    if q.total_cents >= 10000 {
        q.note = Option.Some("free shipping");
    }
    return q;
}

fn sensible(l: Line) -> bool {
    return l.qty >= 1 and l.qty <= 1000;
}

fn price_list(sku: str[16]) -> Option<i64> {
    return match sku {
        "tea" => Option.Some(350),
        "cake" => Option.Some(420),
        "teapot" => Option.Some(9900),
        _ => Option.None,
    };
}

fn main() {
    print("quote asks the host for prices: run it with alang call and --hosts examples/orders.hosts.json");
}

test "adds up what is sold" with price = price_list {
    let q: Quote = quote([Line(sku: "tea", qty: 2), Line(sku: "gold", qty: 1), Line(sku: "cake", qty: 1)]);
    assert q.total_cents == 1120;
    assert q.unknown == ["gold"];
    assert q.note == Option.None;
}

test "free shipping from 100.00" with price = price_list {
    let q: Quote = quote([Line(sku: "teapot", qty: 1), Line(sku: "tea", qty: 1)]);
    assert q.note == Option.Some("free shipping");
}

test "an unbound host fn gets no answer" traps R0015 {
    let q: Quote = quote([Line(sku: "tea", qty: 1)]);
    print(q.total_cents);
}
