Parse a duration such as "1h30m", "45s", "2h", "1h2m3s" or "90m" and return it in seconds.

A duration is one or more parts. Each part is a number written with 1 to 6 ASCII digits (leading
zeros are allowed) followed by a unit: `h` (hours), `m` (minutes) or `s` (seconds). Units appear in
the order h, m, s, each at most once, with nothing between or around the parts. Return -1 if `text`
does not have this form; that includes the empty string, spaces, uppercase units and numbers with more
than 6 digits.

`text` has at most 32 bytes.
