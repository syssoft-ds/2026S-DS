from __future__ import annotations

import argparse
import re
from pathlib import Path

import polars as pl


EXPERIMENT_LABELS = {
    "sim4da-firework": "Sim4Da firework",
    "tokenring-distributed": "Token ring distributed",
    "tokenring": "Token ring local",
}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create a presentation-sized summary table from experiment CSV results."
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "results",
        help="Directory containing experiment result folders.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Optional CSV output path. Defaults to results/experiment-presentation-summary.csv.",
    )
    args = parser.parse_args()

    results_dir = args.results_dir.resolve()
    output_path = args.output or results_dir / "experiment-presentation-summary.csv"

    experiments = load_experiments(results_dir)
    summary = summarize_experiments(experiments)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    summary.write_csv(output_path)

    print(to_markdown(summary))
    print(f"\nCSV written to: {output_path}")


def load_experiments(results_dir: Path) -> pl.DataFrame:
    frames = []
    for raw_path in sorted(results_dir.glob("*/raw-runs.csv")):
        experiment_dir = raw_path.parent
        frame = pl.read_csv(raw_path, null_values=["", "None", "null"], infer_schema_length=None)
        frame = frame.with_columns(
            pl.lit(experiment_name(experiment_dir.name)).alias("Experiment"),
            pl.lit(experiment_dir.name).alias("Result folder"),
        )
        frames.append(frame)

    if not frames:
        raise FileNotFoundError(f"No raw-runs.csv files found below {results_dir}")

    return pl.concat(frames, how="diagonal_relaxed")


def experiment_name(folder_name: str) -> str:
    for prefix, label in EXPERIMENT_LABELS.items():
        if folder_name.startswith(prefix):
            return label

    cleaned = re.sub(r"-\d{8}-\d{6}$", "", folder_name)
    return cleaned.replace("-", " ").title()


def summarize_experiments(results: pl.DataFrame) -> pl.DataFrame:
    rows = []

    for experiment in results.get_column("Experiment").unique(maintain_order=True):
        experiment_rows = results.filter(pl.col("Experiment") == experiment)
        by_n = (
            experiment_rows.group_by("n")
            .agg(
                pl.len().alias("runs"),
                (pl.col("status") != "ok").sum().alias("failed_runs"),
            )
            .sort("n")
        )

        full_success_ns = by_n.filter(pl.col("failed_runs") == 0).get_column("n")
        reference_n = int(full_success_ns.max()) if not full_success_ns.is_empty() else None
        max_tested_n = int(by_n.get_column("n").max())

        reference_rows = (
            experiment_rows.filter((pl.col("n") == reference_n) & (pl.col("status") == "ok"))
            if reference_n is not None
            else experiment_rows.clear()
        )

        failures = int((experiment_rows.get_column("status") != "ok").sum())
        total_runs = experiment_rows.height
        partial_note = partial_larger_n_note(by_n, reference_n, max_tested_n)

        rows.append(
            {
                "Experiment": experiment,
                "n tested": node_range_label(by_n),
                "Runs": total_runs,
                "Failures": failures,
                "Success rate": f"{100 * (total_runs - failures) / total_runs:.1f}%",
                "Largest all-ok n": reference_n,
                "Rounds @ max ok n": mean_value(reference_rows, "rounds", digits=1),
                "Multicasts @ max ok n": mean_value(reference_rows, "multicasts", digits=1),
                "Avg latency ms @ max ok n": mean_value(reference_rows, "avgMs", digits=2),
                "Duration s @ max ok n": mean_value(reference_rows, "durationSeconds", digits=3),
                "Note": partial_note,
            }
        )

    return pl.DataFrame(rows).sort("Experiment")


def node_range_label(by_n: pl.DataFrame) -> str:
    ns = by_n.get_column("n")
    if ns.len() == 1:
        return str(int(ns.item()))

    return f"{int(ns.min())}-{int(ns.max())} ({ns.len()} sizes)"


def partial_larger_n_note(by_n: pl.DataFrame, reference_n: int | None, max_tested_n: int) -> str:
    if reference_n is None or max_tested_n <= reference_n:
        return ""

    larger_rows = by_n.filter(pl.col("n") > reference_n)
    if larger_rows.is_empty():
        return ""

    worst = larger_rows.sort("n").tail(1).row(0, named=True)
    return f"n={int(worst['n'])} had {int(worst['failed_runs'])}/{int(worst['runs'])} failed runs"


def mean_value(frame: pl.DataFrame, column: str, digits: int) -> float | None:
    if frame.is_empty() or column not in frame.columns:
        return None

    value = frame.select(pl.col(column).mean()).item()
    return None if value is None else round(float(value), digits)


def to_markdown(frame: pl.DataFrame) -> str:
    rows = [[format_cell(value) for value in row] for row in frame.rows()]
    headers = frame.columns
    widths = [
        max(len(header), *(len(row[index]) for row in rows))
        for index, header in enumerate(headers)
    ]

    def render_row(values: list[str]) -> str:
        return "| " + " | ".join(
            value.ljust(widths[index]) for index, value in enumerate(values)
        ) + " |"

    header = render_row(headers)
    separator = "| " + " | ".join("-" * width for width in widths) + " |"
    body = [render_row(row) for row in rows]
    return "\n".join([header, separator, *body])


def format_cell(value: object) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:g}"
    return str(value)


if __name__ == "__main__":
    main()
