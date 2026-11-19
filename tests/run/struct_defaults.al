// Field defaults, P() when every field has one, and P(..base, x: v) taking the other fields from base.
const LIMIT: i64 = 5;

struct Config {
    retries: i64 = 3,
    name: str[8] = "job",
    verbose: bool = false,
    limits: [i64; 2] = [LIMIT, LIMIT * 2],
}

struct Point {
    x: i64,
    y: i64 = 0,
}

struct Pair<A, B> {
    first: A,
    second: B,
    tag: u8 = 'p',
}

struct Wrap {
    inner: Point,
    label: str[4] = "w",
}

const ORIGIN: Point = Point(x: 0);
const DEFAULTS: Config = Config();

fn moved(p: Point, dx: i64) -> Point {
    return Point(..p, x: p.x + dx);
}

fn main() {
    let c: Config = Config();
    let d: Config = Config(retries: 9);
    print(c);
    print(d, " ", DEFAULTS == c);
    let e: Config = Config(..d, name: "other", verbose: true);
    print(e);
    let p: Point = Point(x: 4);
    print(p, " ", ORIGIN, " ", moved(p, 3), " ", Point(y: 7, ..p));
    var q: Point = Point(..ORIGIN);
    q.x = 1;
    print(q, " ", ORIGIN);
    let w: Wrap = Wrap(inner: p);
    let w2: Wrap = Wrap(..w, label: "z");
    let w3: Wrap = Wrap(inner: Point(..w.inner, y: 1), ..w2);
    print(w, " ", w2, " ", w3);
    let pr: Pair<i64, bool> = Pair(first: 1, second: true);
    let pr2: Pair<i64, bool> = Pair(..pr, second: false);
    print(pr, " ", pr2, " ", Pair(..pr2, tag: 'q'));
}
