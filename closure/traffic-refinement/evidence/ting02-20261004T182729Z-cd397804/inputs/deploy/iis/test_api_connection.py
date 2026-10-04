#!/usr/bin/env python3
"""Read-only HTTP diagnosis; output never includes response bodies or URLs."""
import argparse
import json
import queue
import socket
import ssl
import threading
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


MAX_RESPONSE_BYTES = 65536
LOOPBACK_HEALTH = 'http://127.0.0.1:8080/api/health'


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def health_url(public_url):
    """Accept a deployment base URL, without userinfo or query secrets."""
    try:
        parts = urlsplit(public_url)
        if (parts.scheme not in ('http', 'https') or not parts.hostname
                or parts.username is not None or parts.password is not None
                or '?' in public_url or '#' in public_url or '\\' in public_url
                or any(character.isspace() for character in public_url)
                or parts.port == 0):
            raise ValueError
    except (TypeError, ValueError):
        raise ValueError('Use an HTTP(S) deployment URL without credentials, query, or fragment.') from None
    return public_url.rstrip('/') + '/api/health'


def empty_result(error=None):
    return {'http_status': None, 'content_type': 'missing', 'redirect': False,
            'valid_health': False, 'exercise_count': None, 'error': error}


def classify_response(status, content_type, body):
    mime = content_type.split(';', 1)[0].strip().lower()
    result = empty_result()
    result.update(http_status=status, redirect=300 <= status < 400,
                  content_type=('json' if mime == 'application/json' else
                                'html' if mime in ('text/html', 'application/xhtml+xml') else
                                'other' if mime else 'missing'))
    if len(body) > MAX_RESPONSE_BYTES:
        result['error'] = 'response_too_large'
        return result
    if result['content_type'] != 'json':
        return result
    try:
        document = json.loads(body)
    except (ValueError, UnicodeError, RecursionError):
        result['error'] = 'invalid_json'
        return result
    if (status == 200 and isinstance(document, dict)
            and set(document) == {'status', 'exercises', 'engine'}
            and document['status'] == 'ok'
            and document['engine'] == 'ACGN / CanDis Fast Rewrite IR'
            and type(document['exercises']) is int and document['exercises'] > 0):
        result.update(valid_health=True, exercise_count=document['exercises'])
    return result


def probe(url, timeout=5):
    """Limit total wall time even when a peer slowly streams a response."""
    answers = queue.Queue(maxsize=1)

    def request():
        try:
            opener = build_opener(ProxyHandler({}), NoRedirects())
            try:
                response = opener.open(Request(url, headers={'Accept': 'application/json'}), timeout=timeout)
            except HTTPError as error:
                response = error
            with response:
                answer = classify_response(response.status, response.headers.get('Content-Type', ''),
                                           response.read(MAX_RESPONSE_BYTES + 1))
        except (TimeoutError, socket.timeout):
            answer = empty_result('timeout')
        except (ssl.SSLError, URLError) as error:
            reason = getattr(error, 'reason', error)
            answer = empty_result('tls_error' if isinstance(reason, ssl.SSLError) else
                                  'timeout' if isinstance(reason, (TimeoutError, socket.timeout)) else
                                  'connection_failed')
        except Exception:
            answer = empty_result('request_failed')
        answers.put(answer)

    threading.Thread(target=request, daemon=True).start()
    try:
        return answers.get(timeout=timeout)
    except queue.Empty:
        return empty_result('timeout')


def diagnosis(public, loopback):
    if public['valid_health'] and loopback['valid_health']:
        return ('healthy' if public['exercise_count'] == loopback['exercise_count']
                else 'catalogue_mismatch')
    if public['http_status'] in (401, 403):
        return 'public_authentication_or_access'
    if public['redirect']:
        return 'public_redirect'
    if not loopback['valid_health']:
        return ('public_available_local_backend_unavailable' if public['valid_health']
                else 'local_backend_unavailable' if loopback['http_status'] is None
                else 'local_backend_unhealthy')
    if public['error'] == 'tls_error':
        return 'public_tls_error'
    return 'public_proxy_or_routing'


def diagnose(public_url, timeout=5, *, loopback_url=LOOPBACK_HEALTH):
    public = probe(health_url(public_url), timeout)
    loopback = probe(loopback_url, timeout)
    category = diagnosis(public, loopback)
    same_count = (public['exercise_count'] == loopback['exercise_count']
                  if public['valid_health'] and loopback['valid_health'] else None)
    return {'status': 'PASS' if category == 'healthy' else 'FAIL', 'diagnosis': category,
            'same_catalogue_count': same_count, 'public': public, 'loopback': loopback}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-url', required=True)
    parser.add_argument('--timeout', type=float, default=5)
    args = parser.parse_args()
    if not 0 < args.timeout <= 30:
        report = {'status': 'FAIL', 'diagnosis': 'invalid_timeout'}
    else:
        try:
            report = diagnose(args.public_url, args.timeout)
        except ValueError:
            report = {'status': 'FAIL', 'diagnosis': 'invalid_public_url'}
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
