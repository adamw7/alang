Merge overlapping intervals.

Each interval has an integer `start` and `end` with `start <= end`; both ends are included. Two
intervals overlap when they share at least one point, so [1, 3] and [3, 5] merge into [1, 5], while
[1, 2] and [3, 4] stay separate. Merge until no two intervals overlap and return the result sorted by
`start`.

The input has at most 16 intervals in any order, and every `start` and `end` is between -10^9 and 10^9.
