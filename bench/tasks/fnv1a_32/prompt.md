Return the 32-bit FNV-1a hash of the UTF-8 bytes of `text`, as a non-negative integer.

Start with h = 2166136261. For each byte b of the UTF-8 encoding of `text`, in order, set h to h XOR b
and then set h to (h * 16777619) mod 2^32. The result is the final h. For example, the hash of the
empty string is 2166136261 and the hash of "a" is 3826002220.

`text` has at most 64 bytes.
