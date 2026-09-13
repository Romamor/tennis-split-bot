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

    def test_network_reads_only_selected_tunnel(self):
        text = "lo: " + " ".join(['999']*16) + "\neth0: " + " ".join(['888']*16) + "\nwg0: " + " ".join(str(x) for x in range(16))
        values = collector.network_metrics(text, 'wg0')
        self.assertEqual(values['vds_vpn_receive_bytes_total'], 0)
        self.assertEqual(values['vds_vpn_transmit_bytes_total'], 8)
        self.assertEqual(values['vds_vpn_receive_errors_total'], 2)
        self.assertEqual(values['vds_vpn_transmit_drops_total'], 11)
        self.assertEqual(len(values), 8)
        with self.assertRaises(ValueError):collector.network_metrics(text, 'missing')

    def test_stopped_container_is_zero_without_stale_resource_metrics(self):
        with patch.object(collector, 'inspect_container', return_value=({'Running':False,'OOMKilled':True},3)), patch.object(collector, 'cgroup_path') as reader:
            values=collector.vpn_metrics('unused', 'wg0')
        self.assertEqual(values, {'vds_vpn_running':0,'vds_vpn_oom_killed':1,'vds_vpn_restarts_total':3})
        reader.assert_not_called()

    def test_failed_vpn_does_not_hide_other_vpn_or_report_zero_memory(self):
        config={name:{'container':name,'interface':'wg0'} for name in ['vpn-1','vpn-2']}
        with patch.object(collector, 'balloon_metrics', return_value={}), patch.object(Path, 'read_text', return_value=''), patch.object(collector, 'container_metrics', return_value={'vds_bot_running':1}), patch.object(collector, 'vpn_metrics', side_effect=[OSError(),{'vds_vpn_running':1,'vds_vpn_memory_bytes':123}]):
            result=collector.collect('bot', config)
        self.assertIn('vds_vpn_collector_success{vpn="vpn-1"} 0',result)
        self.assertNotIn('vds_vpn_memory_bytes{vpn="vpn-1"}',result)
        self.assertIn('vds_vpn_memory_bytes{vpn="vpn-2"} 123',result)
        self.assertIn('vds_bot_running 1',result)

    def test_counter_types_are_declared_once_without_sensitive_labels(self):
        output=collector.exposition({'vds_vpn_restarts_total{vpn="vpn-1"}':1,'vds_vpn_restarts_total{vpn="vpn-2"}':2})
        self.assertEqual(output.count('# TYPE'),1)
        self.assertIn('# TYPE vds_vpn_restarts_total counter',output)

    def test_vpn_configuration_rejects_unsafe_or_incomplete_mapping(self):
        for text in ['[]','{"bad name":{"container":"vpn","interface":"wg0"}}','{"vpn":{"container":"--help","interface":"wg0"}}','{"vpn":{"container":"vpn"}}']:
            with self.assertRaises(ValueError):collector.vpn_configuration(text)
        self.assertEqual(len(collector.vpn_configuration('{"vpn":{"container":"sample-vpn","interface":"wg0"}}')),1)

    def test_vpn_cpu_memory_and_network_units(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            for name,value in {'memory.current':'1024','memory.swap.current':'512','cpu.stat':'usage_usec 2500000\nthrottled_usec 500000\n','memory.events':'oom_kill 2\n'}.items():(path/name).write_text(value)
            original=Path.read_text
            def read(p,*args,**kwargs):
                if str(p)=='/proc/77/net/dev':return 'wg0: '+' '.join(str(x) for x in range(16))
                return original(p,*args,**kwargs)
            with patch.object(collector,'inspect_container',return_value=({'Running':True,'OOMKilled':False,'Pid':77},1)),patch.object(collector,'cgroup_path',return_value=path),patch.object(Path,'read_text',read):values=collector.vpn_metrics('sample','wg0')
        self.assertEqual(values['vds_vpn_cpu_seconds_total'],2.5)
        self.assertEqual(values['vds_vpn_cpu_throttled_seconds_total'],0.5)
        self.assertEqual(values['vds_vpn_oom_kills_total'],2)
        self.assertEqual(values['vds_vpn_memory_bytes'],1024)


if __name__ == '__main__':
    unittest.main()
