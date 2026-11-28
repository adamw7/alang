# A request to summarize

```json
{"csv": "date,category,amount\n2026-11-01,rent,1450.00\n2026-11-02,groceries,82.17\n2026-11-03,groceries,14.5"}
```

gives `{"categories": [{"name": "rent", "cents": 145000, "rows": 1}, {"name": "groceries", "cents": 9667, "rows": 2}], "total_cents": 154667, "bad_lines": [], "skipped": 0}`.
