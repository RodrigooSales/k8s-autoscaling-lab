import tempfile
import unittest
from pathlib import Path

import pandas as pd

from plot_comparison_bubble import build_category_colors, configuration_category
from plot_comparison_common import CONFIGURATION_LABELS
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


if __name__ == "__main__":
    unittest.main()
