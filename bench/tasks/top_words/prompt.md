Return the `k` most frequent words in `text`, most frequent first. Words with the same count are
ordered alphabetically (by byte value). If there are fewer than `k` distinct words, return all of them.

A word is a maximal run of lowercase ASCII letters `a`-`z`; every other byte separates words.

`text` has at most 256 bytes of printable ASCII, no word is longer than 16 letters, and 1 <= k <= 8.
