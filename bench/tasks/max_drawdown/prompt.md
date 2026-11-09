`prices` is a series of daily prices. Return the largest drop from a price to any later price, as a
non-negative number: the maximum of prices[i] - prices[j] over all i < j. Return 0 if the price never
drops, including for lists with fewer than two prices.

There are at most 32 prices, each between 0 and 10^12.
