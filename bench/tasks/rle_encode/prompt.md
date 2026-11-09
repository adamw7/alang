Run-length encode `text`: replace every maximal run of the same byte with the length of the run in
decimal followed by the byte. For example "aaabcc" becomes "3a1b2c" and "abbbbbbbbbbbb" becomes "1a12b".
The empty string encodes to the empty string.

`text` has at most 64 bytes of printable ASCII and contains no digits.
