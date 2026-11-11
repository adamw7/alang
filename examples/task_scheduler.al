// Orders tasks so that each one runs after the tasks it depends on (a topological sort).
// Tasks name their dependencies; the graph lives in fixed-capacity lists, and a cycle or a
// missing dependency comes back as a value an agent can act on, not as an exception.

const MAX_TASKS: i64 = 16;
const MAX_DEPS: i64 = 4;

struct Task {
    name: str[16],
    deps: list[str[16]; MAX_DEPS],
}

enum Plan {
    Order(list[str[16]; MAX_TASKS]),
    DuplicateTask(str[16]),
    UnknownDependency(str[16], str[16]),
    Cycle(list[str[16]; MAX_TASKS]),
}

fn defines(tasks: list[Task; MAX_TASKS], name: str[16]) -> bool {
    for t in tasks {
        if t.name == name {
            return true;
        }
    }
    return false;
}

/// Orders tasks so that each comes after all of its dependencies; among the tasks that are ready,
/// the one listed first goes first. On a cycle, returns the tasks that can never start.
tool fn schedule(tasks: list[Task; MAX_TASKS]) -> Plan {
    let n: i64 = len(tasks);
    // waiting[i]: how many dependencies of task i have not been scheduled yet.
    var waiting: [i64; MAX_TASKS] = [0; MAX_TASKS];
    for i in 0..n {
        for j in 0..i {
            if tasks[j].name == tasks[i].name {
                return Plan.DuplicateTask(tasks[i].name);
            }
        }
        for dep in tasks[i].deps {
            if not defines(tasks, dep) {
                return Plan.UnknownDependency(tasks[i].name, dep);
            }
        }
        waiting[i] = len(tasks[i].deps);
    }

    var done: [bool; MAX_TASKS] = [false; MAX_TASKS];
    var order: list[str[16]; MAX_TASKS] = [];
    // Every round schedules one task, so n rounds schedule them all, unless some are stuck.
    for round in 0..n {
        var next: i64 = -1;
        for i in 0..n {
            if not done[i] and waiting[i] == 0 {
                next = i;
                break;
            }
        }
        if next == -1 {
            var stuck: list[str[16]; MAX_TASKS] = [];
            for i in 0..n {
                if not done[i] {
                    push(mut stuck, tasks[i].name);
                }
            }
            return Plan.Cycle(stuck);
        }
        done[next] = true;
        push(mut order, tasks[next].name);
        for i in 0..n {
            for dep in tasks[i].deps {
                if dep == tasks[next].name {
                    waiting[i] -= 1;
                }
            }
        }
    }
    return Plan.Order(order);
}

fn position(order: list[str[16]; MAX_TASKS], name: str[16]) -> i64 {
    for i in 0..len(order) {
        if order[i] == name {
            return i;
        }
    }
    return -1;
}

const BUILD: [Task; 6] = [
    Task(name: "deploy", deps: ["test", "package"]),
    Task(name: "test", deps: ["compile"]),
    Task(name: "package", deps: ["compile", "docs"]),
    Task(name: "compile", deps: ["fetch"]),
    Task(name: "docs", deps: []),
    Task(name: "fetch", deps: []),
];

fn main() {
    var tasks: list[Task; MAX_TASKS] = [];
    for t in BUILD {
        push(mut tasks, t);
    }
    match schedule(tasks) {
        Plan.Order(order) => {
            for i in 0..len(order) {
                print(i + 1, ". ", order[i]);
            }
        }
        _ => {
            print(schedule(tasks));
        }
    }
    // Make `fetch` wait for `deploy`: a cycle, so only `docs` can still run.
    tasks[5].deps = ["deploy"];
    print(schedule(tasks));
}

test "every task comes after its dependencies" {
    var tasks: list[Task; MAX_TASKS] = [];
    for t in BUILD {
        push(mut tasks, t);
    }
    match schedule(tasks) {
        Plan.Order(order) => {
            assert len(order) == len(tasks);
            for t in tasks {
                for dep in t.deps {
                    assert position(order, dep) < position(order, t.name);
                }
            }
        }
        _ => {
            panic("expected an order");
        }
    }
}

test "independent tasks keep their input order" {
    let tasks: list[Task; MAX_TASKS] = [
        Task(name: "c", deps: []),
        Task(name: "a", deps: []),
        Task(name: "b", deps: []),
    ];
    assert schedule(tasks) == Plan.Order(["c", "a", "b"]);
    assert schedule([]) == Plan.Order([]);
}

test "a repeated dependency is waited for once" {
    let tasks: list[Task; MAX_TASKS] = [
        Task(name: "b", deps: ["a", "a"]),
        Task(name: "a", deps: []),
    ];
    assert schedule(tasks) == Plan.Order(["a", "b"]);
}

test "problems come back as values" {
    let unknown: list[Task; MAX_TASKS] = [Task(name: "a", deps: ["zzz"])];
    assert schedule(unknown) == Plan.UnknownDependency("a", "zzz");
    let twice: list[Task; MAX_TASKS] = [Task(name: "a", deps: []), Task(name: "a", deps: [])];
    assert schedule(twice) == Plan.DuplicateTask("a");
    let cycle: list[Task; MAX_TASKS] = [
        Task(name: "ok", deps: []),
        Task(name: "x", deps: ["y"]),
        Task(name: "y", deps: ["x"]),
        Task(name: "after", deps: ["ok", "y"]),
    ];
    assert schedule(cycle) == Plan.Cycle(["x", "y", "after"]);
    let own: list[Task; MAX_TASKS] = [Task(name: "self", deps: ["self"])];
    assert schedule(own) == Plan.Cycle(["self"]);
}
