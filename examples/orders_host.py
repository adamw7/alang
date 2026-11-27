"""Answers the price host fn of orders.al: reads {"fn": ..., "args": {...}} on stdin, prints the JSON result."""
import json
import sys

PRICES = {"tea": 350, "cake": 420, "teapot": 9900}

call = json.load(sys.stdin)
if call["fn"] != "price":
    sys.exit(f"no host fn {call['fn']} here")
print(json.dumps(PRICES.get(call["args"]["sku"])))
