// expect: W0307
// Input split into lists it may not fit: one line too many, or one field too long, stops the program.
fn main(input: str[4096]) {
    let lines: list[str[80]; 64] = split(input, '\n');
    for line in lines {
        let fields: list[str[16]; 3] = split(line, ", ");
        let ws: list[str[16]; 8] = words(line);
        print(len(fields), " ", len(ws));
    }
    // A constant text is left alone, and so is a list it always fits.
    let fixed_parts: list[str[8]; 2] = split("a,b", ',');
    let small: str[8] = "a b";
    let all: list[str[8]; 9] = split(small, ' ');
    print(fixed_parts, all);
}
