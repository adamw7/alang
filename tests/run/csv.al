// split_csv(s) reads the fields of a CSV record, quotes and all; try_split_csv returns false instead of trapping.
fn show(fields: list[str[16]; 8]) {
    var line: str[128] = "";
    for i, f in fields {
        if i > 0 {
            append(mut line, " | ");
        }
        append(mut line, "<", f, ">");
    }
    print(len(fields), ": ", line);
}

fn main() {
    let a: list[str[16]; 8] = split_csv("a,b,c");
    show(a);
    let b: list[str[16]; 8] = split_csv("1,\"Smith, Jo\",\"said \"\"hi\"\"\",,\"\"");
    show(b);
    let c: list[str[16]; 8] = split_csv("x;\"y;z\"; w ", ';');
    show(c);
    let d: list[str[16]; 8] = split_csv("");
    show(d);
    let e: list[str[16]; 8] = split_csv("a,\"\"\r");
    show(e);
    let f: list[str[16]; 8] = split_csv("a\"b,c,");
    show(f);
    var fields: list[str[16]; 8] = [];
    let lines: [str[40]; 5] = ["ok,\"fine\"", "\"open,quote", "\"x\"y,z", "1,2,3,4,5,6,7,8,9", "toolongtoolongtoolong,x"];
    for line in lines {
        let ok: bool = try_split_csv(mut fields, line);
        print(ok, " ", len(fields));
    }
    let ok: bool = try_split_csv(mut fields, "a\tb", '\t');
    print(ok, " ", fields);
}

test "an unclosed quote traps" traps R0013 {
    let f: list[str[8]; 4] = split_csv("a,\"b");
    print(f);
}

test "text after a closing quote traps" traps R0013 {
    let f: list[str[8]; 4] = split_csv("\"a\"b");
    print(f);
}
