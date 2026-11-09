def merge_intervals(intervals: list[dict]) -> list[dict]:
    merged = []
    for iv in sorted(intervals, key=lambda iv: (iv["start"], iv["end"])):
        if merged and iv["start"] <= merged[-1]["end"]:
            merged[-1]["end"] = max(merged[-1]["end"], iv["end"])
        else:
            merged.append({"start": iv["start"], "end": iv["end"]})
    return merged
