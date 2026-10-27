"""Live HTTP integration tests against Docker Compose, PostgreSQL, Redis and MinIO."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import math
import time
import unittest
from urllib.parse import parse_qs, urlsplit
from lab_http import ROOT, config, new_account, request

VERIFY_EXPIRY = False
GATEWAY = 'http://localhost:8080'


class LiveLabTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.internal = config()['INTERNAL_API_TOKEN']
        cls.user, cls.token = new_account(GATEWAY)
        cls.other, cls.other_token = new_account(GATEWAY)

    def setUp(self):
        # Token bucket is shared by all routes for this identity.
        time.sleep(1.1)

    def test_routing_dashboard_and_order_ownership(self):
        status, _, created = request(GATEWAY, '/orders', 'POST', {
            'productName': 'Keyboard', 'quantity': 1, 'totalAmount': 500000,
            'userId': self.other['id'],
        }, self.token, headers={'X-User-ID': str(self.other['id']), 'X-Internal-Token': 'spoofed'})
        self.assertEqual(201, status)
        self.assertEqual(self.user['id'], created['userId'])
        status, _, body = request(GATEWAY, '/users/me', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(self.user['id'], body['id'])
        self.assertNotIn('passwordHash', body)
        status, _, orders = request(GATEWAY, '/orders/me', token=self.token)
        self.assertEqual(200, status)
        self.assertTrue(any(order['id'] == created['id'] for order in orders))
        status, _, dashboard = request(GATEWAY, '/dashboard?userId=' + str(self.other['id']), token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(self.user['id'], dashboard['user']['id'])
        self.assertTrue(any(order['id'] == created['id'] for order in dashboard['orders']))
        self.assertEqual(404, request(GATEWAY, '/orders/' + str(created['id']), token=self.other_token)[0])
        self.assertEqual([], request(GATEWAY, '/orders/me', token=self.other_token)[2])

    def test_jwt_and_internal_authentication(self):
        for path in ['/users/me', '/orders/me', '/dashboard']:
            self.assertEqual(401, request(GATEWAY, path)[0])
            self.assertEqual(401, request(GATEWAY, path, token='invalid.jwt.signature')[0])
        for port, path in [(8081, '/users/me'), (8082, '/orders/me')]:
            base = f'http://localhost:{port}'
            self.assertEqual(401, request(base, path, headers={'X-User-ID': str(self.user['id'])})[0])
            self.assertEqual(401, request(base, path, token=self.token)[0])
            self.assertEqual(401, request(base, path, internal=self.internal)[0])
            self.assertEqual(200, request(base, path, token=self.token, internal=self.internal)[0])

    def test_redis_ten_requests_per_second_shared_across_routes(self):
        paths = ['/users/me', '/orders/me', '/dashboard'] * 15
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=30) as executor:
            responses = list(executor.map(lambda path: request(GATEWAY, path, token=self.token), paths))
        elapsed = time.perf_counter() - started
        statuses = [item[0] for item in responses]
        self.assertIn(200, statuses)
        self.assertIn(429, statuses)
        self.assertTrue(all(status in (200, 429) for status in statuses), statuses)
        # One shared bucket, allowing for an integer-second Redis refill boundary.
        self.assertLessEqual(statuses.count(200), 10 * (math.ceil(elapsed) + 1))
        for _, headers, _ in responses:
            normalized = {key.lower(): value for key, value in headers.items()}
            self.assertEqual('10', normalized['x-ratelimit-replenish-rate'])
            self.assertEqual('10', normalized['x-ratelimit-burst-capacity'])
        self.assertEqual(200, request(GATEWAY, '/users/me', token=self.other_token)[0])
        time.sleep(1.1)
        self.assertEqual(200, request(GATEWAY, '/users/me', token=self.token)[0])

    def test_avatar_upload_directly_to_minio_and_optional_real_expiry(self):
        status, _, body = request(GATEWAY, '/users/avatar/upload-url', 'POST', {'contentType': 'image/png'}, self.token)
        self.assertEqual(200, status)
        self.assertEqual(300, body['expiresIn'])
        self.assertEqual('PUT', body['method'])
        url = body['uploadUrl']
        parsed = urlsplit(url)
        query = parse_qs(parsed.query)
        self.assertEqual(['300'], query['X-Amz-Expires'])
        self.assertEqual(urlsplit(config().get('MINIO_PUBLIC_ENDPOINT', 'http://localhost:9000')).netloc, parsed.netloc)
        image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aM1cAAAAASUVORK5CYII=')
        self.assertEqual(200, request(url, method='PUT', data=image, headers={'Content-Type': 'image/png'})[0])
        self.assertEqual(403, request(url)[0])  # PUT signature cannot authorize GET.
        status, _, user = request(GATEWAY, '/users/me', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(body['objectKey'], user['avatarObjectKey'])
        if VERIFY_EXPIRY:
            signed_at = datetime.strptime(query['X-Amz-Date'][0], '%Y%m%dT%H%M%SZ').replace(tzinfo=timezone.utc)
            deadline = signed_at + timedelta(seconds=302)
            print('Waiting for the actual 300-second MinIO signature expiry...', flush=True)
            while datetime.now(timezone.utc) < deadline:
                time.sleep(min(10, max(0.01, (deadline - datetime.now(timezone.utc)).total_seconds())))
            self.assertEqual(403, request(url, method='PUT', data=image)[0])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', default=GATEWAY)
    parser.add_argument('--verify-expiry', action='store_true', help='Also wait five minutes and verify MinIO rejects expired PUT')
    args = parser.parse_args()
    GATEWAY, VERIFY_EXPIRY = args.gateway.rstrip('/'), args.verify_expiry
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(LiveLabTests)
    started = time.perf_counter()
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    output = ROOT / 'tests/results'
    output.mkdir(parents=True, exist_ok=True)
    filename = 'integration-expiry.json' if VERIFY_EXPIRY else 'integration.json'
    (output / filename).write_text(json.dumps({
        'timestamp': datetime.now(timezone.utc).isoformat(), 'tests': result.testsRun,
        'failures': len(result.failures), 'errors': len(result.errors),
        'realMinioExpiryVerified': VERIFY_EXPIRY and result.wasSuccessful(),
        'durationSeconds': round(time.perf_counter() - started, 2),
    }, indent=2), encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)
