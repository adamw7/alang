// The subject of while let may be a call with `mut` arguments: it runs once before each iteration.
struct Queue {
    items: list[i64; 8],
    head: i64,
}

fn next(q: mut Queue) -> Option<i64> {
    if q.head >= len(q.items) {
        return Option.None;
    }
    q.head += 1;
    return Option.Some(q.items[q.head - 1]);
}

fn take(xs: mut list[str[4]; 4]) -> Option<str[4]> {
    if len(xs) == 0 {
        return Option.None;
    }
    let last: str[4] = pop(mut xs);
    return Option.Some(last);
}

fn main() {
    var q: Queue = Queue(items: [5, 6, 7], head: 0);
    var sum: i64 = 0;
    while let Option.Some(x) = next(mut q) limit 8 {
        sum += x;
        if x == 6 {
            push(mut q.items, 1);
        }
    }
    print(sum, " ", q.head);
    var words: list[str[4]; 4] = ["a", "bc", "def"];
    while let Option.Some(w) = take(mut words) limit 4 {
        print(w, " ", len(words));
    }
}
