Wrap `text` into lines of at most `width` bytes and return the lines.

Words are separated by single spaces; `text` has no leading, trailing or repeated spaces and no other
whitespace. Fill lines greedily: put as many words on the current line as fit, separated by single
spaces, then start a new line. A word is never split, not even at a hyphen; a word longer than `width`
goes on a line of its own. Return an empty list for the empty string.

`text` has at most 256 bytes of printable ASCII, and 1 <= width <= 80.
