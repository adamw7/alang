// A host fn has no body: the program's host answers each call. A test binds it to a function of its own with `with`,
// so it runs the same way every time; unbound, a call gets no answer and traps R0015.

/// The stock of an item: how many are left, or why it cannot say.
host fn stock(sku: str[16]) -> Result<i64, str[32]>
    requires len(sku) > 0
    ensures not is_ok(result) or unwrap_or(result, 0) >= 0;

/// Tells the warehouse to send an item; nothing comes back.
host fn ship(sku: str[16], qty: i64);

struct Order {
    sku: str[16],
    qty: i64,
}

fn place(o: Order) -> str[48] {
    match stock(o.sku) {
        Result.Ok(n) if n >= o.qty => {
            ship(o.sku, o.qty);
            return "shipped";
        }
        Result.Ok(n) => {
            var s: str[48] = "only ";
            append(mut s, n, " left");
            return s;
        }
        Result.Err(why) => {
            var s: str[48] = "unknown: ";
            append(mut s, why);
            return s;
        }
    }
}

fn apply(f: fn(str[16]) -> Result<i64, str[32]>, sku: str[16]) -> i64 {
    return unwrap_or(f(sku), -1);
}

fn plenty(sku: str[16]) -> Result<i64, str[32]> {
    if sku == "tea" {
        return Result.Ok(10);
    }
    return Result.Err("no such item");
}

fn none_left(sku: str[16]) -> Result<i64, str[32]> {
    return Result.Ok(0);
}

fn negative(sku: str[16]) -> Result<i64, str[32]> {
    return Result.Ok(-1);
}

fn shipped(sku: str[16], qty: i64) {
    print("ship ", qty, " ", sku);
}

fn main() {
    print("an order with no sku breaks the host fn's precondition, before any host is asked:");
    print(place(Order(sku: "", qty: 1)));
}

test "ships what is in stock" with stock = plenty, ship = shipped {
    assert place(Order(sku: "tea", qty: 2)) == "shipped";
    assert place(Order(sku: "tea", qty: 20)) == "only 10 left";
    assert place(Order(sku: "gold", qty: 1)) == "unknown: no such item";
}

test "as a function value too" with stock = plenty {
    assert apply(stock, "tea") == 10;
    assert apply(stock, "gold") == -1;
}

test "a binding is per test" with stock = none_left {
    assert place(Order(sku: "tea", qty: 1)) == "only 0 left";
}

test "the host fn's contract holds for a binding" traps R0007 with stock = negative {
    print(place(Order(sku: "tea", qty: 1)));
}

test "and its precondition" traps R0006 with stock = plenty {
    print(place(Order(sku: "", qty: 1)));
}

test "unbound, nothing answers" traps R0015 with stock = plenty {
    print(place(Order(sku: "tea", qty: 1)));
}
