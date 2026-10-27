"""HTTP helpers for the live lab. Uses only the Python standard library."""
import http.client
import json
import os
from pathlib import Path
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent


def config():
    values = {}
    path = ROOT / '.env'
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                values[key] = value
    values.update(os.environ)
    return values


def request(base, path='', method='GET', data=None, token=None, internal=None, headers=None):
    url = urlsplit(base)
    cls = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection
    hostname = '127.0.0.1' if url.hostname == 'localhost' else url.hostname
    connection = cls(hostname, url.port, timeout=15)
    actual_headers = dict(headers or {})
    # Preserve the signed Host header when connecting to MinIO via IPv4 loopback.
    actual_headers.setdefault('Host', url.netloc)
    if token:
        actual_headers['Authorization'] = 'Bearer ' + token
    if internal:
        actual_headers['X-Internal-Token'] = internal
    if isinstance(data, (dict, list)):
        data = json.dumps(data).encode()
        actual_headers['Content-Type'] = 'application/json'
    target = (url.path or '') + path
    if url.query:
        target += '?' + url.query
    try:
        connection.request(method, target or '/', body=data, headers=actual_headers)
        response = connection.getresponse()
        raw = response.read()
        body = json.loads(raw) if raw and 'application/json' in response.getheader('Content-Type', '') else raw
        return response.status, dict(response.getheaders()), body
    finally:
        connection.close()


def new_account(gateway):
    username = 'lab_' + uuid.uuid4().hex[:16]
    password = uuid.uuid4().hex
    status, _, user = request(gateway, '/auth/register', 'POST', {
        'username': username, 'email': username + '@example.com', 'password': password,
    })
    if status != 201:
        raise RuntimeError(f'Registration returned HTTP {status}')
    status, _, auth = request(gateway, '/auth/login', 'POST', {'username': username, 'password': password})
    if status != 200:
        raise RuntimeError(f'Login returned HTTP {status}')
    return user, auth['accessToken']
