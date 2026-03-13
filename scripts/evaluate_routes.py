#!/usr/bin/env python3
import argparse
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path


ROOT = Path("/Users/sjw/Desktop/black-mamba")
DEFAULT_INPUT = ROOT / "docs" / "experiments" / "od-samples.seoul.json"
DEFAULT_OUTPUT_DIR = ROOT / "output" / "experiments"
CACHE_NAMES = [
    "odsay_route",
    "ddareungi_snapshot",
    "kickboard_snapshot",
    "mobility_availability",
    "tmap_pedestrian_route",
]


def load_samples(path: Path):
    with path.open("r", encoding="utf-8") as fp:
        return json.load(fp)


def slugify(value: str) -> str:
    normalized = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower()).strip("-")
    return normalized or "default"


def fetch_routes(base_url: str, sample: dict, search_mode: str, recommendation_preference: str):
    params = {
        "originLat": sample["origin"]["lat"],
        "originLng": sample["origin"]["lng"],
        "destLat": sample["destination"]["lat"],
        "destLng": sample["destination"]["lng"],
        "searchMode": search_mode,
        "recommendationPreference": recommendation_preference,
    }
    url = f"{base_url.rstrip('/')}/api/routes?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_transit_baseline(base_url: str, sample: dict):
    params = {
        "originLat": sample["origin"]["lat"],
        "originLng": sample["origin"]["lng"],
        "destLat": sample["destination"]["lat"],
        "destLng": sample["destination"]["lng"],
        "searchMode": "SPECIFIC",
    }
    url = f"{base_url.rstrip('/')}/api/routes?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_metric_value(base_url: str, metric_name: str, tags: dict[str, str]) -> float | None:
    params = [("tag", f"{key}:{value}") for key, value in tags.items()]
    query = urllib.parse.urlencode(params)
    url = f"{base_url.rstrip('/')}/actuator/metrics/{metric_name}"
    if query:
        url = f"{url}?{query}"

    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception:
        return None

    measurements = payload.get("measurements") or []
    if not measurements:
        return 0.0
    return float(measurements[0].get("value") or 0.0)


def fetch_cache_metrics_snapshot(base_url: str) -> dict[str, dict[str, float]]:
    snapshot: dict[str, dict[str, float]] = {}
    for cache in CACHE_NAMES:
        hit = fetch_metric_value(base_url, "navigation.cache.total", {"cache": cache, "result": "hit"})
        miss = fetch_metric_value(base_url, "navigation.cache.total", {"cache": cache, "result": "miss"})
        if hit is None and miss is None:
            continue
        snapshot[cache] = {
            "hit": round(hit or 0.0, 3),
            "miss": round(miss or 0.0, 3),
        }
    return snapshot


def diff_cache_metrics(before: dict[str, dict[str, float]], after: dict[str, dict[str, float]]) -> dict[str, dict[str, float]]:
    result: dict[str, dict[str, float]] = {}
    for cache in sorted(set(before.keys()) | set(after.keys())):
        before_entry = before.get(cache, {})
        after_entry = after.get(cache, {})
        hit_before = float(before_entry.get("hit") or 0.0)
        miss_before = float(before_entry.get("miss") or 0.0)
        hit_after = float(after_entry.get("hit") or 0.0)
        miss_after = float(after_entry.get("miss") or 0.0)
        result[cache] = {
            "hitDelta": round(hit_after - hit_before, 3),
            "missDelta": round(miss_after - miss_before, 3),
        }
    return result


def walking_distance(route: dict) -> int:
    evaluation = route.get("evaluation") or {}
    if "walkingDistanceMeters" in evaluation:
        return int(evaluation["walkingDistanceMeters"])
    return sum(int(leg.get("distanceMeters") or 0) for leg in route.get("legs", []) if leg.get("type") == "WALK")


def transfer_count(route: dict) -> int:
    evaluation = route.get("evaluation") or {}
    if "transferCount" in evaluation:
        return int(evaluation["transferCount"])
    transit_legs = sum(1 for leg in route.get("legs", []) if leg.get("type") == "TRANSIT")
    return max(transit_legs - 1, 0)


def baseline_route(routes: list[dict]) -> dict | None:
    return next((route for route in routes if route.get("type") == "TRANSIT_ONLY"), None)


def recommended_route(routes: list[dict]) -> dict | None:
    return next((route for route in routes if route.get("recommended") is True), routes[0] if routes else None)


def best_mixed_route(routes: list[dict]) -> dict | None:
    mixed_routes = [route for route in routes if route.get("type") != "TRANSIT_ONLY"]
    if not mixed_routes:
        return None
    return max(mixed_routes, key=lambda route: float(route.get("score") or 0.0))


def route_summary(route: dict) -> dict:
    evaluation = route.get("evaluation") or {}
    return {
        "type": route.get("type"),
        "minutes": int(route.get("totalMinutes") or 0),
        "costWon": int(route.get("totalCostWon") or 0),
        "score": float(route.get("score") or 0.0),
        "walkingMeters": walking_distance(route),
        "transferCount": transfer_count(route),
        "maxAccessWalkMeters": int(evaluation.get("maxAccessWalkDistanceMeters") or 0),
        "sharedMobilityDependent": bool(evaluation.get("sharedMobilityDependent") or False),
        "reliabilityScore": float(evaluation.get("reliabilityScore") or 0.0),
        "hubs": [
            {
                "name": hub.get("name"),
                "type": hub.get("type"),
                "role": hub.get("role"),
                "source": hub.get("source"),
            }
            for hub in (evaluation.get("hubs") or [])
        ],
    }


def evaluate_sample(sample: dict, payload: dict, baseline_payload: dict) -> dict:
    routes = payload.get("routes") or []
    baseline = baseline_route(baseline_payload.get("routes") or [])
    recommended = recommended_route(routes)
    mixed = best_mixed_route(routes)

    if not baseline or not recommended:
        return {
            "id": sample["id"],
            "origin": sample["origin"],
            "destination": sample["destination"],
            "error": "baseline_or_recommended_missing",
            "routeCount": len(routes),
        }

    base = route_summary(baseline)
    rec = route_summary(recommended)
    mixed_summary = route_summary(mixed) if mixed else None
    return {
        "id": sample["id"],
        "origin": sample["origin"],
        "destination": sample["destination"],
        "routeCount": len(routes),
        "baseline": base,
        "recommended": rec,
        "bestMixed": mixed_summary,
        "delta": {
            "minutes": rec["minutes"] - base["minutes"],
            "walkingMeters": rec["walkingMeters"] - base["walkingMeters"],
            "costWon": rec["costWon"] - base["costWon"],
            "transferCount": rec["transferCount"] - base["transferCount"],
            "score": round(rec["score"] - base["score"], 6),
        },
        "mixedDelta": {
            "minutes": mixed_summary["minutes"] - base["minutes"],
            "walkingMeters": mixed_summary["walkingMeters"] - base["walkingMeters"],
            "costWon": mixed_summary["costWon"] - base["costWon"],
            "transferCount": mixed_summary["transferCount"] - base["transferCount"],
            "score": round(mixed_summary["score"] - base["score"], 6),
        } if mixed_summary else None,
        "sameAsBaseline": rec["type"] == base["type"] and rec["minutes"] == base["minutes"] and rec["costWon"] == base["costWon"],
        "recommendedInsights": (recommended.get("insights") or {}),
        "bestMixedInsights": (mixed.get("insights") or {}) if mixed else {},
    }


def safe_fetch_transit_baseline(base_url: str, sample: dict, optimal_payload: dict) -> tuple[dict, str]:
    try:
        return fetch_transit_baseline(base_url, sample), "SPECIFIC_BASELINE"
    except Exception:
        return optimal_payload, "OPTIMAL_FALLBACK_BASELINE"


def mean(values: list[float]) -> float:
    return round(sum(values) / len(values), 3) if values else 0.0


def aggregate_generation_reason_counts(results: list[dict], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for result in results:
        insights = result.get(key) or {}
        diagnostics = insights.get("generationDiagnostics") or []
        for diagnostic in diagnostics:
            reason_code = diagnostic.get("reasonCode") or "UNKNOWN"
            counts[reason_code] = counts.get(reason_code, 0) + 1
    return dict(sorted(counts.items(), key=lambda item: (-item[1], item[0])))


def build_summary(results: list[dict]) -> dict:
    valid = [result for result in results if "error" not in result]
    if not valid:
        return {
            "sampleCount": len(results),
            "successfulCount": 0,
            "failedCount": len(results),
        }

    minute_deltas = [result["delta"]["minutes"] for result in valid]
    walk_deltas = [result["delta"]["walkingMeters"] for result in valid]
    cost_deltas = [result["delta"]["costWon"] for result in valid]
    score_deltas = [result["delta"]["score"] for result in valid]
    access_walks = [result["recommended"]["maxAccessWalkMeters"] for result in valid]
    mixed = [result for result in valid if result.get("bestMixed")]
    mixed_minute_deltas = [result["mixedDelta"]["minutes"] for result in mixed]
    mixed_walk_deltas = [result["mixedDelta"]["walkingMeters"] for result in mixed]
    mixed_cost_deltas = [result["mixedDelta"]["costWon"] for result in mixed]
    mixed_score_deltas = [result["mixedDelta"]["score"] for result in mixed]

    return {
        "sampleCount": len(results),
        "successfulCount": len(valid),
        "failedCount": len(results) - len(valid),
        "recommendedTransitOnlyCount": sum(1 for result in valid if result["recommended"]["type"] == "TRANSIT_ONLY"),
        "recommendedBikeCount": sum(1 for result in valid if result["recommended"]["type"] == "TRANSIT_WITH_BIKE"),
        "sharedMobilityRecommendedCount": sum(1 for result in valid if result["recommended"]["sharedMobilityDependent"]),
        "fasterThanBaselineCount": sum(1 for result in valid if result["delta"]["minutes"] < 0),
        "slowerThanBaselineCount": sum(1 for result in valid if result["delta"]["minutes"] > 0),
        "cheaperThanBaselineCount": sum(1 for result in valid if result["delta"]["costWon"] < 0),
        "lessWalkingThanBaselineCount": sum(1 for result in valid if result["delta"]["walkingMeters"] < 0),
        "avgMinutesDelta": mean(minute_deltas),
        "avgWalkingDeltaMeters": mean(walk_deltas),
        "avgCostDeltaWon": mean(cost_deltas),
        "avgScoreDelta": mean(score_deltas),
        "avgRecommendedAccessWalkMeters": mean(access_walks),
        "maxRecommendedAccessWalkMeters": max(access_walks) if access_walks else 0,
        "samplesWithMixedAlternative": len(mixed),
        "avgMixedMinutesDelta": mean(mixed_minute_deltas),
        "avgMixedWalkingDeltaMeters": mean(mixed_walk_deltas),
        "avgMixedCostDeltaWon": mean(mixed_cost_deltas),
        "avgMixedScoreDelta": mean(mixed_score_deltas),
        "recommendedGenerationReasonCounts": aggregate_generation_reason_counts(valid, "recommendedInsights"),
        "bestMixedGenerationReasonCounts": aggregate_generation_reason_counts(mixed, "bestMixedInsights"),
    }


def render_markdown(summary: dict, results: list[dict], input_path: Path, base_url: str, search_mode: str, recommendation_preference: str, cache_metrics: dict[str, dict[str, float]] | None) -> str:
    lines = [
        "# Route Evaluation Report",
        "",
        f"- Generated at: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- API base URL: `{base_url}`",
        f"- Search mode: `{search_mode}`",
        f"- Recommendation preference: `{recommendation_preference}`",
        f"- Sample file: `{input_path}`",
        "",
        "## Summary",
        "",
        f"- Samples: `{summary.get('sampleCount', 0)}`",
        f"- Successful: `{summary.get('successfulCount', 0)}`",
        f"- Failed: `{summary.get('failedCount', 0)}`",
    ]

    if summary.get("successfulCount", 0) > 0:
        lines.extend([
            f"- Recommended `TRANSIT_ONLY`: `{summary['recommendedTransitOnlyCount']}`",
            f"- Recommended `TRANSIT_WITH_BIKE`: `{summary['recommendedBikeCount']}`",
            f"- Shared mobility recommended: `{summary['sharedMobilityRecommendedCount']}`",
            f"- Avg minutes delta (recommended - baseline): `{summary['avgMinutesDelta']}`",
            f"- Avg walking delta meters: `{summary['avgWalkingDeltaMeters']}`",
            f"- Avg cost delta won: `{summary['avgCostDeltaWon']}`",
            f"- Avg score delta: `{summary['avgScoreDelta']}`",
            f"- Avg recommended access walk meters: `{summary['avgRecommendedAccessWalkMeters']}`",
            f"- Max recommended access walk meters: `{summary['maxRecommendedAccessWalkMeters']}`",
            f"- Samples with mixed alternatives: `{summary['samplesWithMixedAlternative']}`",
            f"- Avg mixed minutes delta (best mixed - baseline): `{summary['avgMixedMinutesDelta']}`",
            f"- Avg mixed walking delta meters: `{summary['avgMixedWalkingDeltaMeters']}`",
            f"- Avg mixed cost delta won: `{summary['avgMixedCostDeltaWon']}`",
            f"- Avg mixed score delta: `{summary['avgMixedScoreDelta']}`",
        ])

    if cache_metrics:
        lines.extend(["", "## Cache Metrics", ""])
        for cache, values in cache_metrics.items():
            lines.append(
                f"- `{cache}`: hit Δ `{values['hitDelta']}`, miss Δ `{values['missDelta']}`"
            )

    recommended_reason_counts = summary.get("recommendedGenerationReasonCounts") or {}
    best_mixed_reason_counts = summary.get("bestMixedGenerationReasonCounts") or {}
    if recommended_reason_counts or best_mixed_reason_counts:
        lines.extend(["", "## Generation Diagnostics", ""])
        if recommended_reason_counts:
            lines.append("- Recommended routes")
            for reason_code, count in recommended_reason_counts.items():
                lines.append(f"  - `{reason_code}`: `{count}`")
        if best_mixed_reason_counts:
            lines.append("- Best mixed alternatives")
            for reason_code, count in best_mixed_reason_counts.items():
                lines.append(f"  - `{reason_code}`: `{count}`")

    lines.extend([
        "",
        "## Per Sample",
        "",
        "| ID | Baseline | Recommended | Best Mixed | Δ Time | Δ Walk | Δ Cost | Access Walk | Shared |",
        "| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- |",
    ])

    for result in results:
        if "error" in result:
            lines.append(f"| {result['id']} | - | ERROR | - | - | - | - | - | - |")
            continue
        lines.append(
            "| {id} | {base_type} {base_min}m | {rec_type} {rec_min}m | {mixed} | {d_min:+} | {d_walk:+}m | {d_cost:+}원 | {access}m | {shared} |".format(
                id=result["id"],
                base_type=result["baseline"]["type"],
                base_min=result["baseline"]["minutes"],
                rec_type=result["recommended"]["type"],
                rec_min=result["recommended"]["minutes"],
                mixed=(
                    f"{result['bestMixed']['type']} {result['bestMixed']['minutes']}m"
                    if result.get("bestMixed") else "-"
                ),
                d_min=result["delta"]["minutes"],
                d_walk=result["delta"]["walkingMeters"],
                d_cost=result["delta"]["costWon"],
                access=result["recommended"]["maxAccessWalkMeters"],
                shared="Y" if result["recommended"]["sharedMobilityDependent"] else "N",
            )
        )

    lines.extend(["", "## Notes", ""])
    for result in results:
        if "error" in result:
            lines.append(f"- `{result['id']}`: failed with `{result['error']}`")
            continue
        lines.append(
            f"- `{result['id']}`: baseline `{result['baseline']['type']}` vs recommended `{result['recommended']['type']}`, "
            f"bestMixed={result['bestMixed']['type'] if result.get('bestMixed') else '-'}, "
            f"reasons={result['recommendedInsights'].get('recommendationReasons', [])}, "
            f"risks={result['recommendedInsights'].get('riskBadges', [])}"
        )

    return "\n".join(lines) + "\n"


def write_outputs(output_dir: Path, payload: dict, markdown: str, input_path: Path, recommendation_preference: str):
    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    input_slug = slugify(input_path.stem)
    preference_slug = slugify(recommendation_preference)
    json_path = output_dir / f"route-eval-{timestamp}-{input_slug}-{preference_slug}.json"
    md_path = output_dir / f"route-eval-{timestamp}-{input_slug}-{preference_slug}.md"
    latest_json = output_dir / "latest-route-eval.json"
    latest_md = output_dir / "latest-route-eval.md"

    for path in (json_path, latest_json):
        with path.open("w", encoding="utf-8") as fp:
            json.dump(payload, fp, ensure_ascii=False, indent=2)
    for path in (md_path, latest_md):
        path.write_text(markdown, encoding="utf-8")

    return json_path, md_path


def main():
    parser = argparse.ArgumentParser(description="Run batch evaluation for the multimodal routing engine.")
    parser.add_argument("--input", default=str(DEFAULT_INPUT), help="Path to O/D sample JSON")
    parser.add_argument("--base-url", default="http://localhost:8081", help="Base URL for the running backend")
    parser.add_argument("--search-mode", default="OPTIMAL", help="Search mode to evaluate")
    parser.add_argument("--recommendation-preference", default="RELIABILITY", help="Recommendation preference to evaluate")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR), help="Directory to write evaluation results")
    args = parser.parse_args()

    input_path = Path(args.input)
    output_dir = Path(args.output_dir)
    samples = load_samples(input_path)
    cache_before = fetch_cache_metrics_snapshot(args.base_url)

    results = []
    for sample in samples:
        try:
            payload = fetch_routes(args.base_url, sample, args.search_mode, args.recommendation_preference)
            baseline_payload, baseline_source = safe_fetch_transit_baseline(args.base_url, sample, payload)
            result = evaluate_sample(sample, payload, baseline_payload)
            result["baselineSource"] = baseline_source
            results.append(result)
        except Exception as exc:  # noqa: BLE001
            results.append({
                "id": sample["id"],
                "origin": sample["origin"],
                "destination": sample["destination"],
                "error": str(exc),
            })

    summary = build_summary(results)
    cache_after = fetch_cache_metrics_snapshot(args.base_url)
    cache_metrics = diff_cache_metrics(cache_before, cache_after) if cache_before or cache_after else {}
    report_payload = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "baseUrl": args.base_url,
        "searchMode": args.search_mode,
        "recommendationPreference": args.recommendation_preference,
        "input": str(input_path),
        "summary": summary,
        "cacheMetrics": cache_metrics,
        "results": results,
    }
    markdown = render_markdown(summary, results, input_path, args.base_url, args.search_mode, args.recommendation_preference, cache_metrics)
    json_path, md_path = write_outputs(output_dir, report_payload, markdown, input_path, args.recommendation_preference)

    print(f"Wrote JSON report: {json_path}")
    print(f"Wrote Markdown report: {md_path}")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
