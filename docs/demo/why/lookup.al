enum Email {
    Found(str[32]),
    Missing,
}

fn find_email(name: str[16]) -> Email {
    if name == "ada" {
        return Email.Found("ada@example.com");
    }
    return Email.Missing;
}

fn main() {
    match find_email("bob") {
        Email.Found(e) => { print(e); }
    }
}
