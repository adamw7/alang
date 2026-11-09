Compare two version strings such as "1.10.2" and "1.9".

A version is 1 to 8 components separated by single dots; each component is 1 to 9 ASCII digits
(leading zeros are allowed). Compare the components from left to right as integers, so "1.10" is
higher than "1.9" and "1.01" equals "1.1". A missing component counts as 0, so "1.0" equals "1" and
"1.0.1" is higher than "1". Return -1 if `a` is lower than `b`, 1 if it is higher, and 0 if they are
equal.

Both strings are well-formed and have at most 64 bytes.
