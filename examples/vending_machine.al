// A vending machine as a state machine. Events and their effects are enums, one exhaustive
// `match` handles every event, and the machine's state changes only through a `mut` parameter,
// which every call site marks: `handle(mut m, event)`.

struct Product {
    code: str[2],
    name: str[16],
    price: i64,
}

const PRODUCTS: [Product; 3] = [
    Product(code: "A1", name: "Water", price: 100),
    Product(code: "A2", name: "Juice", price: 175),
    Product(code: "B1", name: "Crisps", price: 120),
];

struct Machine {
    credit: i64,
    stock: [i64; 3],
    takings: i64,
}

enum Event {
    Coin(i64),
    Select(str[2]),
    Cancel,
}

enum Effect {
    Credit(i64),
    RejectedCoin(i64),
    Vended(str[16], i64),
    NeedMore(i64),
    SoldOut(str[16]),
    UnknownProduct(str[2]),
    Refunded(i64),
}

enum Lookup {
    At(i64),
    Missing,
}

fn find_product(code: str[2]) -> Lookup {
    for i in 0..len(PRODUCTS) {
        if PRODUCTS[i].code == code {
            return Lookup.At(i);
        }
    }
    return Lookup.Missing;
}

fn is_coin(cents: i64) -> bool {
    match cents {
        5 => { return true; }
        10 => { return true; }
        20 => { return true; }
        50 => { return true; }
        100 => { return true; }
        200 => { return true; }
        _ => { return false; }
    }
}

fn stocked_machine() -> Machine {
    return Machine(credit: 0, stock: [5, 2, 1], takings: 0);
}

fn handle(m: mut Machine, event: Event) -> Effect
    ensures m.credit >= 0
{
    match event {
        Event.Coin(cents) => {
            if not is_coin(cents) {
                return Effect.RejectedCoin(cents);
            }
            m.credit += cents;
            return Effect.Credit(m.credit);
        }
        Event.Select(code) => {
            match find_product(code) {
                Lookup.Missing => {
                    return Effect.UnknownProduct(code);
                }
                Lookup.At(i) => {
                    let p: Product = PRODUCTS[i];
                    if m.stock[i] == 0 {
                        return Effect.SoldOut(p.name);
                    }
                    if m.credit < p.price {
                        return Effect.NeedMore(p.price - m.credit);
                    }
                    let change: i64 = m.credit - p.price;
                    m.stock[i] -= 1;
                    m.takings += p.price;
                    m.credit = 0;
                    return Effect.Vended(p.name, change);
                }
            }
        }
        Event.Cancel => {
            let refund: i64 = m.credit;
            m.credit = 0;
            return Effect.Refunded(refund);
        }
    }
}

/// Runs up to 32 events on a freshly stocked machine and returns the effect of each one.
/// Events: {"Coin": [cents]} (5, 10, 20, 50, 100 or 200), {"Select": ["A1"]} (A1 Water 100,
/// A2 Juice 175, B1 Crisps 120) and "Cancel".
tool fn simulate(events: list[Event; 32]) -> list[Effect; 32] {
    var m: Machine = stocked_machine();
    var effects: list[Effect; 32] = [];
    for e in events {
        let effect: Effect = handle(mut m, e);
        push(mut effects, effect);
    }
    return effects;
}

fn main() {
    var m: Machine = stocked_machine();
    let events: [Event; 13] = [
        Event.Select("A2"),
        Event.Coin(100),
        Event.Coin(3),
        Event.Coin(50),
        Event.Select("A2"),
        Event.Coin(50),
        Event.Select("A2"),
        Event.Coin(200),
        Event.Select("B1"),
        Event.Coin(100),
        Event.Select("B1"),
        Event.Select("Z9"),
        Event.Cancel,
    ];
    for e in events {
        let effect: Effect = handle(mut m, e);
        print(e, " -> ", effect);
    }
    print(m);
}

test "change is returned with the product" {
    var m: Machine = stocked_machine();
    let paid: Effect = handle(mut m, Event.Coin(200));
    assert paid == Effect.Credit(200);
    let sold: Effect = handle(mut m, Event.Select("A1"));
    assert sold == Effect.Vended("Water", 100);
    assert m.credit == 0;
    assert m.stock[0] == 4;
    assert m.takings == 100;
}

test "selecting without enough credit keeps the credit" {
    var m: Machine = stocked_machine();
    let paid: Effect = handle(mut m, Event.Coin(100));
    assert paid == Effect.Credit(100);
    // A call with a `mut` argument is a statement of its own, so the result is stored first.
    let short: Effect = handle(mut m, Event.Select("A2"));
    assert short == Effect.NeedMore(75);
    assert m.credit == 100;
    let cancelled: Effect = handle(mut m, Event.Cancel);
    assert cancelled == Effect.Refunded(100);
    assert m.credit == 0;
}

test "a sold-out product keeps the credit" {
    let effects: list[Effect; 32] = simulate([
        Event.Coin(200),
        Event.Select("B1"),
        Event.Coin(200),
        Event.Select("B1"),
    ]);
    assert effects[1] == Effect.Vended("Crisps", 80);
    assert effects[3] == Effect.SoldOut("Crisps");
}

test "invalid coins and codes change nothing" {
    var m: Machine = stocked_machine();
    let before: Machine = m;
    let coin: Effect = handle(mut m, Event.Coin(7));
    assert coin == Effect.RejectedCoin(7);
    let code: Effect = handle(mut m, Event.Select("C3"));
    assert code == Effect.UnknownProduct("C3");
    assert m == before;
}

test "money is conserved" {
    let events: list[Event; 32] = [
        Event.Coin(100), Event.Coin(100), Event.Select("A2"), Event.Coin(50),
        Event.Coin(20), Event.Select("B1"), Event.Coin(200), Event.Cancel, Event.Coin(10),
    ];
    var m: Machine = stocked_machine();
    var inserted: i64 = 0;
    var returned: i64 = 0;
    for e in events {
        let effect: Effect = handle(mut m, e);
        match effect {
            Effect.Credit(_) => {
                match e {
                    Event.Coin(cents) => { inserted += cents; }
                    _ => { }
                }
            }
            Effect.Vended(_, change) => { returned += change; }
            Effect.Refunded(cents) => { returned += cents; }
            _ => { }
        }
    }
    assert inserted == m.takings + returned + m.credit;
}
