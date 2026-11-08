import json
from pathlib import Path
import runpy
import unittest


class DashboardTest(unittest.TestCase):
    def test_generated_dashboard_is_reproducible_and_panels_do_not_overlap(self):
        path = Path(__file__).with_name('vds-memory.dashboard.json')
        original = path.read_bytes()
        runpy.run_path(str(path.with_name('build_dashboard.py')))
        self.assertEqual(path.read_bytes(), original)
        dashboard = json.loads(original)
        panels = dashboard['panels']
        self.assertEqual(len({p['id'] for p in panels}), len(panels))
        for i, panel in enumerate(panels):
            a = panel['gridPos']
            for previous in panels[:i]:
                b = previous['gridPos']
                overlap = (a['x'] < b['x'] + b['w'] and b['x'] < a['x'] + a['w'] and
                           a['y'] < b['y'] + b['h'] and b['y'] < a['y'] + a['h'])
                self.assertFalse(overlap, (panel['title'], previous['title']))
        expressions = [t['expr'] for p in panels for t in p.get('targets', [])]
        self.assertTrue(any('histogram_quantile(0.95, sum by (le,category)' in e for e in expressions))
        self.assertTrue(all('vpn=~"$vpn"' in e for e in expressions if 'vds_vpn_' in e))
        variable = next(v for v in dashboard['templating']['list'] if v['name'] == 'vpn')
        self.assertTrue(variable['query']['query'].startswith('query_result('))
        self.assertIsNone(variable['allValue'], 'All must expand current aliases, not match retired series')


if __name__ == '__main__':
    unittest.main()
