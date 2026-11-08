#!/usr/bin/env python3
"""Publish numeric ballooning and container metrics for Alloy's textfile collector."""
import argparse
import json
import math
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


def inspect_container(container):
    # Never read or export container environment variables or labels.
    result = subprocess.run(
        ['docker', 'inspect', '--format', '{{json .State}} {{.RestartCount}}', container],
        capture_output=True, text=True, timeout=8, check=True)
    state_text, restarts = result.stdout.strip().rsplit(' ', 1)
    state = json.loads(state_text)
    return state, int(restarts)


def cgroup_path(pid):
    group = next(line[3:] for line in Path(f'/proc/{pid}/cgroup').read_text().splitlines()
                 if line.startswith('0::'))
    root = Path('/sys/fs/cgroup').resolve()
    path = (root / group.lstrip('/')).resolve()
    if not path.is_relative_to(root):
        raise ValueError('Invalid cgroup path')
    return path


def container_metrics(container):
    state, restarts = inspect_container(container)
    values = {'vds_bot_running': int(state['Running']),
              'vds_bot_oom_killed': int(state['OOMKilled']),
              'vds_bot_restart_count': int(restarts)}
    if not state['Running']:
        return values
    pid = int(state['Pid'])
    path = cgroup_path(pid)
    for filename, metric in [('memory.current', 'vds_bot_memory_bytes'),
                             ('memory.swap.current', 'vds_bot_swap_bytes')]:
        values[metric] = int((path / filename).read_text())
    return values


def network_metrics(text, interface):
    for line in text.splitlines():
        name, separator, data = line.partition(':')
        if separator and name.strip() == interface:
            fields = [int(x) for x in data.split()]
            if len(fields) != 16 or min(fields) < 0:
                raise ValueError('Invalid network counters')
            return {f'vds_vpn_{direction}_{metric}_total': fields[offset + index]
                    for direction, offset in [('receive', 0), ('transmit', 8)]
                    for metric, index in [('bytes', 0), ('packets', 1), ('errors', 2), ('drops', 3)]}
    raise ValueError('External network interface missing')


def vpn_metrics(container, interface):
    state, restarts = inspect_container(container)
    values = {'vds_vpn_running': int(state['Running']),
              'vds_vpn_oom_killed': int(state['OOMKilled']),
              'vds_vpn_restarts_total': restarts}
    if not state['Running']:
        return values
    pid = int(state['Pid'])
    path = cgroup_path(pid)
    values['vds_vpn_memory_bytes'] = int((path/'memory.current').read_text())
    values['vds_vpn_swap_bytes'] = int((path/'memory.swap.current').read_text())
    cpu = dict(line.split() for line in (path/'cpu.stat').read_text().splitlines())
    values['vds_vpn_cpu_seconds_total'] = int(cpu['usage_usec']) / 1_000_000
    if 'throttled_usec' in cpu:
        values['vds_vpn_cpu_throttled_seconds_total'] = int(cpu['throttled_usec']) / 1_000_000
    events = dict(line.split() for line in (path/'memory.events').read_text().splitlines())
    values['vds_vpn_oom_kills_total'] = int(events['oom_kill'])
    # Read only the selected tunnel interface; do not add the encrypted outer traffic.
    values.update(network_metrics(Path(f'/proc/{pid}/net/dev').read_text(), interface))
    return values


def vpn_configuration(text):
    mapping = json.loads(text)
    if not isinstance(mapping, dict) or len(mapping) > 8:
        raise ValueError('VPN configuration must be a small alias-to-container map')
    for alias, settings in mapping.items():
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', alias) or not isinstance(settings, dict) or set(settings) != {'container', 'interface'}:
            raise ValueError('Invalid VPN settings')
        if not isinstance(settings['container'], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', settings['container']):
            raise ValueError('Invalid VPN container name')
        if not isinstance(settings['interface'], str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,15}', settings['interface']):
            raise ValueError('Invalid VPN interface')
    return mapping


def exposition(values):
    output = []; declared = set()
    for name, value in sorted(values.items()):
        base = name.split('{', 1)[0]
        if base not in declared:
            kind = 'counter' if base.endswith('_total') else 'gauge'
            output.append(f'# TYPE {base} {kind}\n'); declared.add(base)
        output.append(f'{name} {value}\n')
    return ''.join(output)


def collect(container, vpns=None):
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
    for alias, target in (vpns or {}).items():
        suffix = '{vpn=' + json.dumps(alias) + '}'
        try:
            measurements = vpn_metrics(target['container'], target['interface'])
            measurements['vds_vpn_collector_success'] = 1
        except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError):
            measurements = {'vds_vpn_collector_success': 0}
        values.update({name + suffix: value for name, value in measurements.items()})
    return exposition(values)


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


def ui_metrics(path):
    """Accept only the bounded numeric application format, never arbitrary textfile contents."""
    with Path(path).open('rb') as source:
        raw = source.read(131073)
    if len(raw) > 131072:
        raise ValueError('Application metrics too large')
    text = raw.decode('ascii')
    categories = {'all', 'other', 'participation', 'transfer', 'finance', 'poll', 'input', 'training', 'settings', 'menu'}
    methods = {'other', 'getMe', 'getChatMember', 'getChatAdministrators', 'sendMessage', 'sendRichMessage',
               'sendPoll', 'stopPoll', 'editMessageText', 'editMessageReplyMarkup', 'editEphemeralMessageText',
               'deleteEphemeralMessage', 'deleteMessage', 'pinChatMessage', 'unpinChatMessage', 'answerCallbackQuery'}
    outcomes = {'success', 'retry', 'uncertain', 'rejected', 'error', 'ignored', 'evicted'}
    outcomes |= {'api_' + value for value in ['retry_later', 'uncertain', 'rejected', 'not_modified', 'message_missing', 'callback_expired']}
    names = {'tennis_ui_attempts_total': 'counter', 'tennis_ui_sample_timestamp_seconds': 'gauge',
             'tennis_ui_started_timestamp_seconds': 'gauge'}
    for phase in ('response', 'queue', 'processing', 'api'):
        names['tennis_ui_' + phase + '_seconds'] = 'histogram'
    seen = set()
    for line in text.splitlines():
        if line.startswith('# TYPE '):
            if line not in {f'# TYPE {name} {kind}' for name, kind in names.items()}:
                raise ValueError('Unexpected metric declaration')
            continue
        match = re.fullmatch(r'(tennis_ui_[a-z_]+)(\{[^{}]*\})? ([0-9.eE+\-]+)', line)
        if not match:
            raise ValueError('Unexpected metric')
        name, labels, number = match.groups()
        value = float(number)
        if not math.isfinite(value) or value < 0:
            raise ValueError('Invalid metric value')
        labels = labels or ''
        pairs = re.findall(r'([a-z]+)="([A-Za-z0-9_.+]+)"', labels)
        if labels and '{' + ','.join(f'{k}="{v}"' for k, v in pairs) + '}' != labels:
            raise ValueError('Invalid labels')
        fields = dict(pairs)
        if len(fields) != len(pairs):
            raise ValueError('Duplicate labels')
        if name in {'tennis_ui_sample_timestamp_seconds', 'tennis_ui_started_timestamp_seconds'}:
            valid = not fields
        elif name == 'tennis_ui_attempts_total':
            valid = set(fields) == {'outcome', 'category'} and fields['outcome'] in outcomes and fields['category'] in categories
        else:
            histogram = re.fullmatch(r'tennis_ui_(response|queue|processing|api)_seconds_(bucket|sum|count)', name)
            valid = False
            if histogram:
                phase, suffix = histogram.groups()
                label = 'method' if phase == 'api' else 'category'
                allowed = methods if phase == 'api' else categories
                expected = {label, 'le'} if suffix == 'bucket' else {label}
                valid = set(fields) == expected and fields.get(label) in allowed
                if suffix == 'bucket':
                    valid = valid and fields.get('le') in {'0.1', '0.25', '0.5', '1.0', '2.0', '5.0', '10.0', '30.0', '60.0', '300.0', '3600.0', '86400.0', '+Inf'}
        key = name + labels
        if not valid or key in seen:
            raise ValueError('Unexpected or duplicate metric series')
        seen.add(key)
    if 'tennis_ui_sample_timestamp_seconds' not in seen:
        raise ValueError('Missing application timestamp')
    return text


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--ui-metrics', type=Path, default=os.environ.get('BOT_UI_METRICS_FILE'))
    args = parser.parse_args()
    text = collect(args.container, vpn_configuration(os.environ.get('VPN_CONTAINERS', '{}')))
    if args.ui_metrics:
        try:
            text += ui_metrics(args.ui_metrics)
            text += 'vds_ui_metrics_collector_success 1\n'
        except (OSError, ValueError, UnicodeError):
            text += 'vds_ui_metrics_collector_success 0\n'
    if args.output:
        publish(args.output, text)
    else:
        print(text, end='')
