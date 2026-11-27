// Option<T> in JSON is null for Option.None and the value itself for Option.Some, and a struct field of that type
// may be left out; an Option of an Option keeps the variant form, as null could stand for either.
struct Contact {
    name: str[16],
    email: Option<str[32]>,
    age: Option<u8>,
    tags: list[Option<i64>; 4],
    nested: Option<Option<i64>>,
}

fn show(text: str[256]) {
    let c: Option<Contact> = from_json(text);
    match c {
        Option.Some(v) => { print(v.name, " ", v.email, " ", v.age, " ", v.tags, " ", v.nested); }
        Option.None => { print("not a contact: ", text); }
    }
}

fn main() {
    let a: Contact = Contact(name: "ann", email: Option.Some("a@x.io"), age: Option.None, tags: [Option.Some(1), Option.None],
        nested: Option.Some(Option.None));
    print(to_json(a));
    let text: str[256] = to_json(a);
    let back: Option<Contact> = from_json(text);
    print(back == Option.Some(a));
    show(r"""{"name": "bob", "tags": [], "nested": "None"}""");
    show(r"""{"name": "cy", "email": null, "age": 7, "tags": [null, 2], "nested": {"Some": [3]}}""");
    show(r"""{"name": "dee", "email": "null", "tags": []}""");
    show(r"""{"name": "eve", "age": 300, "tags": []}""");
    show(r"""{"name": "gil", "tags": []}""");
    show(r"""{"name": "fay", "tags": [], "nested": null}""");
    let n: Option<Option<i64>> = from_json("null");
    let s: Option<Option<i64>> = from_json("5");
    print(n, " ", s);
    print(to_json(Option.Some(5)), " ", to_json(Option.Some("x")));
    let none: Option<i64> = Option.None;
    print(to_json(none));
}
