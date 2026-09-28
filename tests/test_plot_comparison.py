import tempfile
import unittest
from pathlib import Path

import pandas as pd

from plot_comparison_aggregated import prepare_plot_data as prepare_aggregated_data
from plot_comparison_bubble import (
    REQUIRED_METRICS,
    build_category_colors,
    configuration_category,
    prepare_plot_data as prepare_bubble_data,
)
from plot_comparison_common import COMPARISON_METRICS, CONFIGURATION_LABELS
from plot_helper import discover_result_files


class CSAGoPlotsTest(unittest.TestCase):
    profiles = ("h", "hq_25", "hq_50", "v", "vq")

    def test_discovery_labels_all_go_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory)
            for run in ("01", "50"):
                for profile in self.profiles:
                    order = 3 if profile.startswith("h") else 6
                    (results / f"{run}_{order}_csa_go_{profile}.csv").touch()
            discovered = discover_result_files(results, CONFIGURATION_LABELS)

        self.assertEqual(len(discovered), 10)
        self.assertEqual(set(discovered["run"]), {"01", "50"})
        self.assertEqual(
            set(discovered["label"]),
            {"CSA Go H", "CSA Go HQ 25", "CSA Go HQ 50", "CSA Go V", "CSA Go VQ"},
        )

    def test_go_has_its_own_bubble_category(self):
        for configuration in ("csa_go", *(f"csa_go_{profile}" for profile in self.profiles)):
            with self.subTest(configuration=configuration):
                self.assertEqual(configuration_category(configuration), "CSA Go")
        self.assertEqual(configuration_category("csa_h"), "CSA")
        self.assertEqual(configuration_category("csa_java_h"), "CSA Java")

    def test_adding_go_preserves_reference_bubble_colors(self):
        references = [
            f"{implementation}_{profile}"
            for implementation in ("csa", "csa_java")
            for profile in self.profiles
        ]
        original = build_category_colors(pd.DataFrame({"configuration": references}))
        combined = build_category_colors(pd.DataFrame({
            "configuration": references + [f"csa_go_{profile}" for profile in self.profiles]
        }))

        pd.testing.assert_series_equal(original, combined.iloc[:len(references)])
        go_colors = combined.iloc[len(references):]
        self.assertTrue(go_colors.notna().all())
        self.assertTrue(set(go_colors).isdisjoint(original))

    def test_plots_group_implementations_by_scenario(self):
        expected = [
            "base_1", "base_5", "hpa_std", "hpa_fast",
            "csa_h", "csa_java_h", "csa_go_h",
            "csa_hq_25", "csa_java_hq_25", "csa_go_hq_25",
            "csa_hq_50", "csa_java_hq_50", "csa_go_hq_50",
            "base_1500", "vpa",
            "csa_v", "csa_java_v", "csa_go_v",
            "csa_vq", "csa_java_vq", "csa_go_vq",
        ]
        rows = []
        for position, configuration in enumerate(reversed(expected)):
            row = {
                "run": "01",
                "order": position,
                "configuration": configuration,
                "label": CONFIGURATION_LABELS[configuration],
            }
            row.update({metric.key: 1.0 for metric in COMPARISON_METRICS})
            rows.append(row)
        run_df = pd.DataFrame(rows)

        configurations, _ = prepare_aggregated_data(run_df)
        self.assertEqual(configurations["configuration"].tolist(), expected)

        summary_rows = [
            {
                "order": row["order"],
                "configuration": row["configuration"],
                "label": row["label"],
                "metric": metric,
                "mean": 1.0,
            }
            for row in rows
            for metric in REQUIRED_METRICS
        ]
        bubble_df = prepare_bubble_data(pd.DataFrame(summary_rows))
        self.assertEqual(bubble_df["configuration"].tolist(), expected)

    def test_csa_labels_preserve_implementation_and_scenario(self):
        expected = {
            "csa_h": "CSA Python H",
            "csa_java_h": "CSA Java H",
            "csa_go_h": "CSA Go H",
            "csa_hq_25": "CSA Python HQ 25",
            "csa_java_hq_25": "CSA Java HQ 25",
            "csa_go_hq_25": "CSA Go HQ 25",
            "csa_hq_50": "CSA Python HQ 50",
            "csa_java_hq_50": "CSA Java HQ 50",
            "csa_go_hq_50": "CSA Go HQ 50",
            "csa_v": "CSA Python V",
            "csa_java_v": "CSA Java V",
            "csa_go_v": "CSA Go V",
            "csa_vq": "CSA Python VQ",
            "csa_java_vq": "CSA Java VQ",
            "csa_go_vq": "CSA Go VQ",
        }

        self.assertEqual(
            {configuration: CONFIGURATION_LABELS[configuration] for configuration in expected},
            expected,
        )


if __name__ == "__main__":
    unittest.main()
