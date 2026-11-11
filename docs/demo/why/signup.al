struct User {
    name: str[8],
    is_admin: bool,
}

fn main() {
    var u: User = User(name: "", is_admin: false);
    append(mut u.name, "mallory99");
    print("admin: ", u.is_admin);
}
