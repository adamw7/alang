// Fixed-capacity strings: building, indexing, comparing, iterating.
fn main() {
    var s: str[64] = "";
    append(mut s, "int=", -42, " u=", u64(7), " f=", 2.5, " b=", true, " c=", u8(120));
    push(mut s, 'x');
    print(s);
    print(len(s), " ", cap(s));
    push(mut s, '!');
    let last: u8 = pop(mut s);
    print(last, " ", len(s));
    var t: str[8] = "abc";
    t[0] = 'A';
    print(t, " ", t[1], " ", t == "Abc", " ", t != "abc");
    let wide: str[128] = t;
    print(wide == t, " ", cap(wide));
    clear(mut t);
    print("[", t, "] ", len(t), " ", t == "");
    var count: i32 = 0;
    for c in "hello" {
        if c == 'l' {
            count += 1;
        }
    }
    print("l count: ", count);
    let quoted: [str[16]; 3] = ["tab\there", "quote\"q", "nl\nback\\"];
    print(quoted);
    print("caf\xc3\xa9");
    match "go" {
        "stop" => { print("stopping"); }
        "go" => { print("going"); }
        _ => { print("unknown"); }
    }
}
