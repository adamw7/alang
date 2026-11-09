Return true if `number` passes the Luhn checksum, false otherwise.

`number` is valid only if it consists of 2 to 32 ASCII digits `0`-`9` and nothing else (no spaces, signs
or other characters). Starting from the rightmost digit, double every second digit (the second from
the right, the fourth from the right, and so on); when a doubled value is greater than 9, subtract 9
from it. Add up all the digits (doubled or not); the number is valid if the total is divisible by 10.

`number` has at most 32 bytes.
