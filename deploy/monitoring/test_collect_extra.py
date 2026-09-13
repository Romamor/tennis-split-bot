import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import collect_extra as collector


class ExtraMetricsTest(unittest.TestCase):
    def test_balloon_uses_current_not_cumulative_target_counter(self):
        values = collector.balloon_metrics('target : 15\ncurrent : 16\ntarget : 900 (0 failed)\n', 4096)
        self.assertEqual(values['vds_balloon_current_bytes'], 65536)
        self.assertEqual(values['vds_balloon_target_bytes'], 61440)

    def test_missing_metrics_are_not_reported_as_zero(self):
        with patch.object(Path, 'read_text', side_effect=OSError), patch.object(collector, 'container_metrics', side_effect=OSError):
            output = collector.collect('example-container')
        self.assertIn('vds_balloon_collector_success 0\n', output)
        self.assertIn('vds_bot_collector_success 0\n', output)
        self.assertNotIn('vds_bot_memory_bytes', output)
        self.assertNotIn('vds_balloon_current_bytes', output)

    def test_atomic_replacement_drops_obsolete_readings(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'extra.prom'
            collector.publish(output, 'old 1\n')
            collector.publish(output, 'current 2\n')
            self.assertEqual(output.read_text(), 'current 2\n')
            self.assertEqual(list(Path(directory).iterdir()), [output])


if __name__ == '__main__':
    unittest.main()
