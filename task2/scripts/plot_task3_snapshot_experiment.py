from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd
import seaborn as sns
import matplotlib

matplotlib.use("Agg")
from matplotlib import pyplot as plt


BLUE_PALETTE = ["#0070C0", "#5B9BD5", "#A8CAEC"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    data = pd.read_csv(args.csv)
    data["node_count"] = data["node_count"].astype(str)
    data["frequency"] = pd.Categorical(
        data["frequency"],
        categories=["fast", "medium", "slow"],
        ordered=True,
    )
    frequency_labels = {}
    for frequency, group in data.groupby("frequency", observed=True):
        mean_wait_ms = (group["min_wait_ms"].iloc[0] + group["max_wait_ms"].iloc[0]) / 2
        mean_transfers_per_second = 1000 / mean_wait_ms
        frequency_labels[frequency] = f"{frequency} ({mean_transfers_per_second:.1f}/s)"
    data["frequency_label"] = data["frequency"].map(frequency_labels)

    sns.set_theme(style="whitegrid", context="talk")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    naive_output = args.output.with_name(f"{args.output.stem}-naive-error{args.output.suffix}")
    coloring_output = args.output.with_name(f"{args.output.stem}-coloring-overhead{args.output.suffix}")

    naive_fig, naive_ax = plt.subplots(figsize=(8, 6), constrained_layout=True)

    sns.barplot(
        data=data,
        x="node_count",
        y="naive_average_abs_error",
        hue="frequency_label",
        ax=naive_ax,
        palette=BLUE_PALETTE,
    )
    naive_ax.set_title("Naive snapshot error")
    naive_ax.set_xlabel("Number of bank nodes (n)")
    naive_ax.set_ylabel("Average absolute error")
    naive_ax.legend(title="Transfer frequency")
    naive_fig.savefig(naive_output, dpi=180, bbox_inches="tight")
    plt.close(naive_fig)

    coloring_fig, coloring_ax = plt.subplots(figsize=(8, 6), constrained_layout=True)

    sns.lineplot(
        data=data,
        x="node_count",
        y="coloring_control_messages",
        hue="frequency_label",
        marker="o",
        linewidth=2.5,
        ax=coloring_ax,
        palette=BLUE_PALETTE,
    )
    coloring_ax.set_title("Coloring snapshot overhead")
    coloring_ax.set_xlabel("Number of bank nodes (n)")
    coloring_ax.set_ylabel("Control messages")
    coloring_ax.legend(title="Transfer frequency")
    coloring_fig.savefig(coloring_output, dpi=180, bbox_inches="tight")
    plt.close(coloring_fig)


if __name__ == "__main__":
    main()
