"""Paired, sequential, paced direct-vs-gateway latency benchmark. No third-party packages."""
import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
import http.client
import json
import math
import platform
import statistics
import time
from urllib.parse import urlsplit
from lab_http import ROOT, config, new_account, request


class Endpoint:
    def __init__(self, url, token, internal=None):
        parsed = urlsplit(url)
        # Docker ports bind IPv4 loopback. Avoid Windows localhost -> ::1 fallback
        # when Tomcat periodically closes a keep-alive connection.
        hostname = '127.0.0.1' if parsed.hostname == 'localhost' else parsed.hostname
        self.connection = http.client.HTTPConnection(hostname, parsed.port, timeout=15)
        self.headers = {'Authorization': 'Bearer ' + token}
        if internal:
            self.headers['X-Internal-Token'] = internal

    def measure(self, path):
        start = time.perf_counter_ns()
        try:
            self.connection.request('GET', path, headers=self.headers)
            response = self.connection.getresponse()
            response.read()  # Include complete body transfer in elapsed time.
            status = response.status
        except (OSError, http.client.HTTPException):
            self.connection.close()
            status = 0
        return (time.perf_counter_ns() - start) / 1_000_000, status


def summary(samples):
    successful = sorted(ms for ms, status in samples if status == 200)
    percentile = lambda fraction: successful[max(0, math.ceil(len(successful) * fraction) - 1)] if successful else None
    return {
        'requests': len(samples), 'successRatePercent': round(len(successful) * 100 / len(samples), 2),
        'averageMs': round(statistics.mean(successful), 3) if successful else None,
        'p50Ms': round(percentile(.50), 3) if successful else None,
        'p95Ms': round(percentile(.95), 3) if successful else None,
        'statuses': dict(Counter(str(status) for _, status in samples)),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=int, default=100, help='Measured samples per endpoint per path')
    parser.add_argument('--warmup', type=int, default=10)
    parser.add_argument('--rps', type=float, default=4, help='Maximum combined gateway requests/sec, must be <= 5')
    parser.add_argument('--gateway', default='http://localhost:8080')
    args = parser.parse_args()
    if args.samples < 1 or args.warmup < 0 or not 0 < args.rps <= 5:
        parser.error('samples >= 1, warmup >= 0, and 0 < rps <= 5 are required')
    settings = config()
    _, token = new_account(args.gateway)
    status, _, _ = request(args.gateway, '/orders', 'POST', {
        'productName': 'Benchmark keyboard', 'quantity': 1, 'totalAmount': 500000,
    }, token)
    if status != 201:
        raise RuntimeError(f'Benchmark setup returned HTTP {status}')
    time.sleep(1.1)
    endpoints = {
        'users': (Endpoint('http://localhost:8081', token, settings['INTERNAL_API_TOKEN']), Endpoint(args.gateway, token)),
        'orders': (Endpoint('http://localhost:8082', token, settings['INTERNAL_API_TOKEN']), Endpoint(args.gateway, token)),
    }
    collected = {name: {'direct': [], 'gateway': []} for name in endpoints}
    raw = []
    try:
        for index in range(args.warmup + args.samples):
            for name, pair in endpoints.items():
                start = time.perf_counter()
                # Alternate order to reduce a consistent cache/scheduling advantage.
                for kind, endpoint in (list(zip(('direct', 'gateway'), pair))[::(-1 if index % 2 else 1)]):
                    sample = endpoint.measure('/' + name + '/me')
                    if sample[1] == 429:
                        raise RuntimeError('HTTP 429 encountered; discard benchmark and rerun without concurrent traffic')
                    if index >= args.warmup:
                        collected[name][kind].append(sample)
                        raw.append({'endpoint': name, 'path': kind, 'latencyMs': round(sample[0], 6), 'status': sample[1]})
                time.sleep(max(0, 1 / args.rps - (time.perf_counter() - start)))
            if (index + 1) % 25 == 0:
                print(f'Completed {index + 1}/{args.warmup + args.samples} sample pairs per service', flush=True)
    finally:
        for pair in endpoints.values():
            for endpoint in pair:
                endpoint.connection.close()
    report = {
        'timestamp': datetime.now(timezone.utc).isoformat(), 'platform': platform.platform(),
        'python': platform.python_version(), 'gatewayRequestsPerSecond': args.rps,
        'warmupPerEndpoint': args.warmup, 'percentiles': 'nearest rank',
        'notes': 'Persistent HTTP/1.1 connections; IPv4 loopback for localhost to avoid IPv6 fallback; sequential paired requests; both paths validate JWT and access the same DB; successful responses only for latency statistics.',
        'services': {},
    }
    valid = True
    for name, paths in collected.items():
        stats = {kind: summary(samples) for kind, samples in paths.items()}
        all_success = all(stats[kind]['successRatePercent'] == 100 for kind in paths)
        valid &= all_success
        stats['averageOverheadMs'] = round(stats['gateway']['averageMs'] - stats['direct']['averageMs'], 3) if all_success else None
        report['services'][name] = stats
    report['validComparison'] = valid
    output = ROOT / 'tests/results'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'benchmark.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    with (output / 'benchmark.csv').open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=['endpoint', 'path', 'latencyMs', 'status'])
        writer.writeheader()
        writer.writerows(raw)
    print(json.dumps(report, indent=2))
    return 0 if valid else 1


if __name__ == '__main__':
    raise SystemExit(main())
