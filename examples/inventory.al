// An inventory service meant to be driven by an AI agent through tools.
// All state lives in a fixed-capacity value that is passed in and returned:
// no heap, no globals, no hidden side effects.

const MAX_ITEMS: i64 = 32;

struct Item {
    sku: str[16],
    name: str[32],
    quantity: i32,
    price_cents: i64,
}

struct Inventory {
    items: list[Item; MAX_ITEMS],
}

enum Lookup {
    Found(i64),
    Missing,
}

enum OrderResult {
    Accepted(i64),
    UnknownSku(str[16]),
    OutOfStock(str[16], i32),
}

fn find(inv: Inventory, sku: str[16]) -> Lookup {
    for i in 0..len(inv.items) {
        if inv.items[i].sku == sku {
            return Lookup.Found(i);
        }
    }
    return Lookup.Missing;
}

fn restock(inv: mut Inventory, sku: str[16], name: str[32], quantity: i32, price_cents: i64)
    requires quantity > 0
    requires price_cents >= 0
{
    match find(inv, sku) {
        Lookup.Found(i) => {
            inv.items[i].quantity += quantity;
        }
        Lookup.Missing => {
            push(mut inv.items, Item(sku: sku, name: name, quantity: quantity, price_cents: price_cents));
        }
    }
}

fn order(inv: mut Inventory, sku: str[16], quantity: i32) -> OrderResult
    requires quantity > 0
{
    match find(inv, sku) {
        Lookup.Missing => {
            return OrderResult.UnknownSku(sku);
        }
        Lookup.Found(i) => {
            let available: i32 = inv.items[i].quantity;
            if available < quantity {
                return OrderResult.OutOfStock(sku, available);
            }
            inv.items[i].quantity -= quantity;
            return OrderResult.Accepted(i64(quantity) * inv.items[i].price_cents);
        }
    }
}

fn stock_value(inv: Inventory) -> i64 {
    var total: i64 = 0;
    for item in inv.items {
        total += i64(item.quantity) * item.price_cents;
    }
    return total;
}

fn demo_inventory() -> Inventory {
    var inv: Inventory = Inventory(items: []);
    restock(mut inv, "A-100", "USB cable", 25, 499);
    restock(mut inv, "B-200", "Keyboard", 4, 3999);
    restock(mut inv, "C-300", "Monitor", 2, 18900);
    return inv;
}

/// Places an order against the demo inventory and reports the outcome.
/// Accepted orders return the total price in cents.
tool fn place_order(sku: str[16], quantity: i32) -> OrderResult
    requires quantity > 0
{
    var inv: Inventory = demo_inventory();
    return order(mut inv, sku, quantity);
}

/// Total value of the demo inventory in cents.
tool fn inventory_value() -> i64 {
    return stock_value(demo_inventory());
}

fn main() {
    var inv: Inventory = demo_inventory();
    print("stock value: ", stock_value(inv));
    let r1: OrderResult = order(mut inv, "B-200", 3);
    print("order 3 x B-200: ", r1);
    let r2: OrderResult = order(mut inv, "B-200", 3);
    print("order 3 x B-200: ", r2);
    let r3: OrderResult = order(mut inv, "Z-999", 1);
    print("order 1 x Z-999: ", r3);
    print("stock value: ", stock_value(inv));
}

test "orders reduce stock" {
    var inv: Inventory = demo_inventory();
    let r: OrderResult = order(mut inv, "A-100", 5);
    assert r == OrderResult.Accepted(2495);
    assert inv.items[0].quantity == 20;
}

test "cannot oversell" {
    var inv: Inventory = demo_inventory();
    let r: OrderResult = order(mut inv, "C-300", 3);
    assert r == OrderResult.OutOfStock("C-300", 2);
    assert inv.items[2].quantity == 2;
}

test "restocking an existing sku adds quantity" {
    var inv: Inventory = demo_inventory();
    restock(mut inv, "C-300", "Monitor", 5, 18900);
    assert len(inv.items) == 3;
    assert inv.items[2].quantity == 7;
}
