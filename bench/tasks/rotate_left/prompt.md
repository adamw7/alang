Rotate `xs` to the left by `k` positions: the element at index i moves to index (i - k) mod n, where n is
the length of `xs`. For example, rotating [1, 2, 3, 4, 5] left by 2 gives [3, 4, 5, 1, 2]. `k` may be
negative (which rotates to the right) and may be larger than n. The empty list stays empty.

`xs` has at most 32 elements, and -10^9 <= k <= 10^9.
