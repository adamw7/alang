Convert a dotted IPv4 address such as "192.168.0.1" to its 32-bit value as a non-negative integer
(3232235521 for that example).

A valid address is exactly four numbers separated by single dots. Each number is 1 to 3 ASCII digits
`0`-`9`, between 0 and 255, with no leading zeros ("0" is valid, "01" and "00" are not). Return -1 for
anything else, including the empty string, spaces, signs, other characters and missing or extra dots.

`text` has at most 32 bytes.
