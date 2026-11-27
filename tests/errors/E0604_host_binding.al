// expect: E0604
host fn price(sku: str[16]) -> i64;

fn cents(sku: str[8]) -> i64 {
    return 100;
}

fn flat(sku: str[16]) -> i64 {
    return 100;
}

test "wrong type" with price = cents {
    assert price("a") == 100;
}

test "not a host fn" with flat = cents {
    assert flat("a") == 100;
}

test "bound twice" with price = flat, price = flat {
    assert price("a") == 100;
}
