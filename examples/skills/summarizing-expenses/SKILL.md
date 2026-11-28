---
name: summarizing-expenses
description: "Sum a CSV export of expenses per category with the summarize tool of examples/expenses.al. Use when the user shares bank or card transactions, a budget sheet or receipts and asks where the money went."
license: MIT
---
# Summarizing expenses

1. Get the rows into the CSV `summarize` takes: a header line, then one `date,category,amount` line per
   expense, the amount in units with at most two decimals (`12.50`, `-3.07`). Put a category on rows that
   have none; keep the user's own names for categories.
2. Call `summarize` with `{"csv": "<the text>"}`. At most 16384 bytes go in one call: split a longer export
   by month and add the results up yourself.
3. Read the result: `categories` comes largest total first, in cents; `total_cents` is their sum. Write cents
   as money (`145000` is `1450.00`).
4. If `skipped` is not 0, say so, and show the lines in `bad_lines` (numbered from 1, the header being line 1)
   so the user can fix them. A category past the 32nd is skipped too: merge small categories and call again.

See [an example of what to send](examples/request.md).
