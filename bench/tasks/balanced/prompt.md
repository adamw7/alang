Return true if the brackets in `text` are balanced, false otherwise.

The brackets are `(`, `)`, `[`, `]`, `{` and `}`. They are balanced when every opening bracket is
closed by the matching closing bracket, brackets close in the reverse order they were opened, and no
closing bracket appears without a matching opening bracket. All other bytes are ignored. Text without
brackets (including the empty string) is balanced.

`text` has at most 128 bytes of printable ASCII.
