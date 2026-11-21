// isqrt, sort_by_key, and lists kept as binary heaps (priority queues).
struct Task {
    name: str[8],
    priority: i64,
}

fn priority(t: Task) -> i64 {
    return t.priority;
}

fn name_length(t: Task) -> (i64, str[8]) {
    return (len(t.name), t.name);
}

fn urgent_first(a: Task, b: Task) -> bool {
    return a.priority > b.priority;
}

fn main() {
    print(isqrt(0), " ", isqrt(1), " ", isqrt(17), " ", isqrt(1_000_000), " ", isqrt(u64(18_446_744_073_709_551_615)));
    print(isqrt(i64(9_223_372_036_854_775_807)), " ", isqrt(u8(255)), " ", isqrt(i8(120)));

    var tasks: list[Task; 8] = [
        Task(name: "write", priority: 2),
        Task(name: "ship", priority: 3),
        Task(name: "test", priority: 2),
        Task(name: "plan", priority: 1),
    ];
    sort_by_key(mut tasks, priority);
    for t in tasks {
        print(t.name, " ", t.priority);
    }
    sort_by_key(mut tasks, name_length);
    print(tasks);

    var heap: list[i64; 16] = [];
    for x in [5, 3, 8, 1, 9, 2, 7, 3] {
        heap_push(mut heap, x);
    }
    print(heap[0], " ", len(heap));
    var order: list[i64; 16] = [];
    while len(heap) > 0 limit 16 {
        let x: i64 = heap_pop(mut heap);
        push(mut order, x);
    }
    print(order);

    var queue: list[Task; 8] = [];
    for t in tasks {
        heap_push(mut queue, t, urgent_first);
    }
    while len(queue) > 0 limit 8 {
        let t: Task = heap_pop(mut queue, urgent_first);
        print(t.priority, " ", t.name);
    }

    var words: list[str[8]; 8] = [];
    for w in ["pear", "fig", "apple", "fig"] {
        heap_push(mut words, w);
    }
    let first: str[8] = heap_pop(mut words);
    print(first, " ", words[0]);
}

test "a heap gives its elements smallest first" {
    var h: list[i64; 8] = [];
    for x in [4, -1, 7, 0] {
        heap_push(mut h, x);
    }
    let a: i64 = heap_pop(mut h);
    let b: i64 = heap_pop(mut h);
    assert a == -1 and b == 0;
}

test "isqrt of a negative number traps" traps R0013 {
    let n: i64 = -4;
    let r: i64 = isqrt(n);
    print(r);
}

test "popping an empty heap traps" traps R0012 {
    var h: list[i64; 2] = [];
    let x: i64 = heap_pop(mut h);
    print(x, len(h));
}
