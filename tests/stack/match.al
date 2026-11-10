// A match on a call result reads the 400 KB enum where the call left it. The C backend used to copy it
// into a second temporary that the bound did not count, so at -O0 this program overran its stack.
enum Big {
    Data([i64; 50000]),
    Nothing,
}

fn make(k: i64) -> Big {
    if k > 0 {
        return Big.Data([k; 50000]);
    }
    return Big.Nothing;
}

fn deep(k: i64) -> i64 {
    var a: [i64; 200000] = [0; 200000];
    a[k] = k;
    return a[k] + a[199999];
}

fn use_it(k: i64) -> i64 {
    match make(k) {
        Big.Data(d) => {
            return deep(d[0]);
        }
        Big.Nothing => {
            return 0;
        }
    }
}

fn main() {
    print(use_it(3));
}
