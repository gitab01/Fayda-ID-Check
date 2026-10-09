"""Threshold evaluation for the two exported models.

This is the script the stub backend's docstring points at: it turns a scored evaluation set into
the false-accept / false-reject numbers that ``threshold_profiles`` is allowed to be tuned against.
It never touches a training split, and it never writes a model — a threshold chosen on data the
model saw during training is the single easiest way to fake a good number.

Input is one CSV per model, with a header::

    liveness:  label,score        # label = genuine | attack, score = attack probability
    match:     label,score        # label = match | non_match, score = cosine similarity (0..1)

Usage::

    python training/evaluate.py --kind liveness --input attacks.csv
    python training/evaluate.py --kind match --input pairs.csv --thresholds 0.40,0.48,0.55
    python training/evaluate.py --self-test     # checks the maths on a known synthetic sample

The printed table is what goes into the profile row; ``version`` in that row is what the decision
record stamps, so a later re-tuning stays interpretable.
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from typing import Sequence

import numpy as np

#: Which side of the threshold is the rejection, and what the two labels are called.
@dataclass(frozen=True)
class Metric:
    kind: str
    positive_label: str   # the sample that must be REJECTED when thresholds are right
    negative_label: str   # the sample that must be ACCEPTED
    reject_above: bool    # True: reject when score > t; False: reject when score < t


METRICS = {
    # CONTRACT §4: attackScore > min_liveness -> FAIL.
    "liveness": Metric("liveness", "attack", "genuine", reject_above=True),
    # CONTRACT §4: similarity < min_match -> FAIL.
    "match": Metric("match", "non_match", "match", reject_above=False),
}

DEFAULT_THRESHOLDS = {
    "liveness": (0.25, 0.30, 0.35, 0.40, 0.45, 0.50),
    "match": (0.40, 0.44, 0.48, 0.52, 0.56, 0.60),
}


@dataclass(frozen=True)
class Row:
    threshold: float
    false_accept_rate: float
    false_reject_rate: float
    false_accepts: int   # positives (must be caught) the threshold let through
    false_rejects: int   # negatives (must pass) the threshold stopped


def _load(path: str, metric: Metric) -> tuple[np.ndarray, np.ndarray]:
    labels: list[int] = []
    scores: list[float] = []
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not {"label", "score"} <= set(reader.fieldnames):
            raise SystemExit(f"{path}: header must be label,score")
        for line in reader:
            label = (line["label"] or "").strip()
            if label == metric.positive_label:
                labels.append(1)
            elif label == metric.negative_label:
                labels.append(0)
            else:
                raise SystemExit(f"{path}: unknown label {label!r}, expected "
                                 f"{metric.positive_label!r} or {metric.negative_label!r}")
            try:
                scores.append(float(line["score"]))
            except (TypeError, ValueError):
                raise SystemExit(f"{path}: score {line['score']!r} is not a number")
    if not labels:
        raise SystemExit(f"{path}: no rows found")
    return np.asarray(labels, dtype=int), np.asarray(scores, dtype=float)


def _rejects(score: float, threshold: float, reject_above: bool) -> bool:
    return score > threshold if reject_above else score < threshold


def evaluate(
    labels: np.ndarray,
    scores: np.ndarray,
    thresholds: Sequence[float],
    *,
    reject_above: bool,
) -> list[Row]:
    positives = scores[labels == 1]   # must be rejected
    negatives = scores[labels == 0]   # must be accepted
    if positives.size == 0 or negatives.size == 0:
        raise SystemExit("need at least one sample of each label to compute FAR and FRR")
    rows: list[Row] = []
    for threshold in sorted(thresholds):
        false_accepts = int(sum(not _rejects(float(s), threshold, reject_above) for s in positives))
        false_rejects = int(sum(_rejects(float(s), threshold, reject_above) for s in negatives))
        rows.append(
            Row(
                threshold=float(threshold),
                # False accept is the expensive error — it is what lets a forged ID through —
                # so a profile is chosen by targeting FAR first and reading the FRR it costs.
                false_accept_rate=false_accepts / positives.size,
                false_reject_rate=false_rejects / negatives.size,
                false_accepts=false_accepts,
                false_rejects=false_rejects,
            )
        )
    return rows


def auc(labels: np.ndarray, scores: np.ndarray) -> float:
    """Rank-based AUC (Mann-Whitney): P(a random positive outranks a random negative).

    Ties score 0.5 each. Higher means the raw scores separate the two labels, independent of any
    threshold — which is why a model can be reported as good here and still need re-tuning.
    """
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(scores.size, dtype=float)
    sorted_scores = scores[order]
    i = 0
    while i < scores.size:
        j = i
        while j + 1 < scores.size and sorted_scores[j + 1] == sorted_scores[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2.0 + 1.0
        i = j + 1
    positives = labels == 1
    negatives = labels == 0
    if not positives.any() or not negatives.any():
        return float("nan")
    rank_sum = ranks[positives].sum()
    n_pos = int(positives.sum())
    n_neg = int(negatives.sum())
    return float((rank_sum - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg))


def separation_auc(labels: np.ndarray, scores: np.ndarray, *, reject_above: bool) -> float:
    """AUC oriented so that above 0.5 always means the two populations are separable.

    The raw rank AUC is P(a positive outscores a negative). A liveness positive (attack) is
    supposed to outscore, so the raw value is already in the rejection direction; a match positive
    (non_match) is supposed to score LOW, so its complement is the useful number.
    """
    area = auc(labels, scores)
    return area if reject_above else 1.0 - area


def _synthetic(reject_above: bool) -> tuple[np.ndarray, np.ndarray]:
    """A known sample, 700 rows with a fixed overlap, laid out the way each metric scores.

    Liveness attacks score high; match non-matches score low. Same two distributions either way, so
    the self-test checks the maths rather than the shape of one metric's data.
    """
    rng = np.random.default_rng(20260501)
    low = rng.normal(0.18, 0.08, 400).clip(0, 1)
    high = rng.normal(0.72, 0.14, 300).clip(0, 1)
    positive, negative = (high, low) if reject_above else (low, high)
    labels = np.concatenate((np.ones(positive.size, dtype=int), np.zeros(negative.size, dtype=int)))
    return labels, np.concatenate((positive, negative))


def _print_table(metric: Metric, rows: Sequence[Row], n_pos: int, n_neg: int) -> None:
    print(f"model kind : {metric.kind}")
    print(f"direction  : reject when score {'>' if metric.reject_above else '<'} threshold")
    print(f"counts     : {metric.positive_label}={n_pos}  {metric.negative_label}={n_neg}")
    print()
    header = f"{'threshold':>10} | {'FAR (bad accepts)':>18} | {'FRR (good rejects)':>19}"
    print(header)
    print("-" * len(header))
    for row in rows:
        print(f"{row.threshold:>10.2f} | {row.false_accept_rate * 100:>17.2f}% "
              f"| {row.false_reject_rate * 100:>18.2f}%")


def _thresholds(args: argparse.Namespace, kind: str) -> list[float]:
    raw = args.thresholds or ",".join(str(t) for t in DEFAULT_THRESHOLDS[kind])
    try:
        return [float(token) for token in raw.split(",") if token.strip()]
    except ValueError:
        raise SystemExit(f"--thresholds must be comma-separated numbers, got {raw!r}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=sorted(METRICS), default="liveness")
    parser.add_argument("--input", help="CSV with header label,score")
    parser.add_argument("--thresholds", help="comma-separated candidate thresholds")
    parser.add_argument("--self-test", action="store_true", help="run the maths on a synthetic sample")
    args = parser.parse_args(argv)

    metric = METRICS[args.kind]

    if args.self_test:
        labels, scores = _synthetic(metric.reject_above)
        thresholds = _thresholds(args, metric.kind)
        rows = evaluate(labels, scores, thresholds, reject_above=metric.reject_above)
        n_pos, n_neg = int((labels == 1).sum()), int((labels == 0).sum())
        _print_table(metric, rows, n_pos, n_neg)

        rates = [row.false_accept_rate for row in rows]
        if metric.reject_above:
            assert all(a <= b + 1e-9 for a, b in zip(rates, rates[1:])), \
                "FAR fell as the rejection threshold rose above the attacks"
        else:
            assert all(a >= b - 1e-9 for a, b in zip(rates, rates[1:])), \
                "FAR fell as the acceptance floor rose — the comparison is inverted"
        assert all(0.0 <= row.false_accept_rate <= 1.0 and 0.0 <= row.false_reject_rate <= 1.0
                   for row in rows), "a rate left [0, 1]"
        assert all(row.false_accepts <= n_pos and row.false_rejects <= n_neg for row in rows), \
            "an error count exceeded the population it came from"
        area = separation_auc(labels, scores, reject_above=metric.reject_above)
        assert area > 0.5, "the oriented AUC must beat chance on a sample this separated"
        print(f"\nAUC (synthetic, oriented): {area:.4f}")
        print("self-test OK")
        return 0

    if not args.input:
        parser.error("--input is required unless --self-test is set")
    labels, scores = _load(args.input, metric)
    rows = evaluate(labels, scores, _thresholds(args, metric.kind), reject_above=metric.reject_above)
    _print_table(metric, rows, int((labels == 1).sum()), int((labels == 0).sum()))
    print(f"\nAUC (oriented): {separation_auc(labels, scores, reject_above=metric.reject_above):.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
