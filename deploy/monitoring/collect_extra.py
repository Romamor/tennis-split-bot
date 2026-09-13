#!/usr/bin/env python3
"""Publish numeric ballooning and container metrics for Alloy's textfile collector."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


def balloon_metrics(text, page_size):
    values = {}
    for field in ('current', 'target'):
        match = re.search(r'^' + field + r'\s*:\s*(\d+)\s*$', text, re.MULTILINE)
        if not match:
            raise ValueError('Missing balloon counter')
        values['vds_balloon_' + field + '_bytes'] = int(match.group(1)) * page_size
    return values


def container_metrics(container):
    # Never read or export container environment variables or labels.
    result = subprocess.run(
        ['docker', 'inspect', '--format', '{{json .State}} {{.RestartCount}}', container],
        capture_output=True, text=True, timeout=8, check=True)
    state_text, restarts = result.stdout.strip().rsplit(' ', 1)
    state = json.loads(state_text)
    values = {'vds_bot_running': int(state['Running']),
              'vds_bot_oom_killed': int(state['OOMKilled']),
              'vds_bot_restart_count': int(restarts)}
    if not state['Running']:
        return values
    pid = int(state['Pid'])
    group = next(line[3:] for line in Path(f'/proc/{pid}/cgroup').read_text().splitlines()
                 if line.startswith('0::'))
    root = Path('/sys/fs/cgroup').resolve()
    path = (root / group.lstrip('/')).resolve()
    if not path.is_relative_to(root):
        raise ValueError('Invalid cgroup path')
    for filename, metric in [('memory.current', 'vds_bot_memory_bytes'),
                             ('memory.swap.current', 'vds_bot_swap_bytes')]:
        values[metric] = int((path / filename).read_text())
    return values


def collect(container):
    values = {'vds_extra_sample_timestamp_seconds': time.time()}
    try:
        values.update(balloon_metrics(Path('/sys/kernel/debug/vmmemctl').read_text(),
                                      os.sysconf('SC_PAGE_SIZE')))
        values['vds_balloon_collector_success'] = 1
    except (OSError, ValueError):
        values['vds_balloon_collector_success'] = 0
    try:
        values.update(container_metrics(container))
        values['vds_bot_collector_success'] = 1
    except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError):
        values['vds_bot_collector_success'] = 0
    # A failed read is missing data, never a misleading zero-memory reading.
    return ''.join(f'# TYPE {name} gauge\n{name} {value}\n' for name, value in sorted(values.items()))


def publish(output, text):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.extra-', dir=output.parent)
    try:
        with os.fdopen(fd, 'w') as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
            os.fchmod(f.fileno(), 0o644)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    text = collect(args.container)
    if args.output:
        publish(args.output, text)
    else:
        print(text, end='')
