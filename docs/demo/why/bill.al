/// Splits a bill evenly; the remainder goes to the first payers.
tool fn split_bill(total_cents: i64, people: i64) -> list[i64; 16]
    requires people > 0 and people <= 16
{
    var shares: list[i64; 16] = [];
    for i in 0..people {
        var share: i64 = total_cents / people;
        if i < total_cents % people {
            share += 1;
        }
        push(mut shares, share);
    }
    return shares;
}
