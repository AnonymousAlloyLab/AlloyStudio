"""Strict, pure HTTP syntax and framing at the bounded ingress boundary.

The independent contract lives in closure/traffic-refinement/strict-decoder-spec.json.
Raw header pairs preserve multiplicity; no email MIME parameter interpretation is used.
"""
import ipaddress
import re

from traffic_http import HTTPInputError

METHOD_PATTERN = r"[!#$%&'*+.^_`|~0-9A-Za-z-]+"
TARGET_PATTERN = r"/(?:[!$&'()*+,./0-9:;=?@A-Z_\[\]a-z~-]|%[0-9A-Fa-f]{2})*"
ESCAPED_FORBIDDEN_PATTERN = r'.*%(?:0[0-9a-f]|1[0-9a-f]|7f|5c).*'
HEADER_NAME_PATTERN = r"[!#$%&'*+.^_`|~0-9A-Za-z-]+"
HEADER_VALUE_PATTERN = r'[\t\x20-\x7e\x80-\xff]*'
HOST_PATTERN = r'(?:[A-Za-z0-9._~-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?'
CONTENT_TYPE_PATTERN = r'application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|utf8|"utf-8"|"utf8"))?[ \t]*'
CONTENT_LENGTH_PATTERN = r'0|[1-9][0-9]{0,7}'
VERSIONS = ('HTTP/1.0', 'HTTP/1.1')
SINGLETON_HEADERS = ('origin', 'host', 'content-length', 'content-type', 'content-encoding',
                     'x-csrf-token', 'sec-fetch-site', 'if-none-match', 'expect',
                     'authorization', 'cookie')


def _require(condition, status, message):
    if not condition:
        raise HTTPInputError(status, message)


def strict_request_line(raw):
    """Return exact method/target/version; reject stdlib's permissive whitespace."""
    _require(raw.endswith(b'\r\n'), 400, 'Request lines must use CRLF.')
    try:
        line = raw[:-2].decode('ascii')
    except UnicodeError:
        raise HTTPInputError(400, 'Invalid request line.') from None
    parts = line.split(' ')
    _require(len(parts) == 3, 400, 'Invalid request line.')
    method, target, version = parts
    _require(re.fullmatch(METHOD_PATTERN, method) is not None, 400, 'Invalid request method.')
    _require(version in VERSIONS, 400, 'Unsupported HTTP version.')
    _require(re.fullmatch(TARGET_PATTERN, target) is not None, 400, 'Invalid request target.')
    _require(not target.startswith('//'), 400, 'Invalid request target.')
    _require(re.fullmatch(ESCAPED_FORBIDDEN_PATTERN, target.lower()) is None,
             400, 'Invalid request target.')
    return method, target, version


def _host_valid(host):
    if re.fullmatch(HOST_PATTERN, host) is None:
        return False
    if host.startswith('['):
        end = host.index(']')
        try:
            ipaddress.IPv6Address(host[1:end])
        except ipaddress.AddressValueError:
            return False
        suffix = host[end + 1:]
    else:
        _, separator, port = host.partition(':')
        suffix = separator + port
    if suffix and int(suffix[1:]) > 65535:
        return False
    return True


def strict_request_headers(raw_pairs, version, *, mutation=False, admin=False):
    """Validate all raw fields and return the unique canonical body length."""
    _require(version in VERSIONS, 400, 'Unsupported HTTP version.')
    values = {}
    for name, value in raw_pairs:
        _require(re.fullmatch(HEADER_NAME_PATTERN, name) is not None,
                 400, 'Malformed request header.')
        _require(re.fullmatch(HEADER_VALUE_PATTERN, value) is not None,
                 400, 'Malformed request header.')
        key = name.lower()
        values.setdefault(key, []).append(value.strip(' \t'))
    for name in SINGLETON_HEADERS:
        _require(len(values.get(name, [])) <= 1, 400,
                 'Duplicate administrator request header.' if admin else 'Duplicate request header.')
    hosts = values.get('host', [])
    _require(version != 'HTTP/1.1' or len(hosts) == 1, 400, 'HTTP/1.1 requires one Host header.')
    _require(not hosts or _host_valid(hosts[0]), 400, 'Invalid Host header.')
    _require('transfer-encoding' not in values, 400, 'Transfer encoding is not supported.')
    _require(values.get('content-encoding', ['identity'])[0].lower() == 'identity',
             415, 'Content encoding is not supported.')
    _require('expect' not in values, 417, 'Expect is not supported.')
    content_types = values.get('content-type', [])
    _require(not mutation or len(content_types) == 1, 415, 'Send application/json.')
    _require(not content_types or re.fullmatch(CONTENT_TYPE_PATTERN, content_types[0].lower()) is not None,
             415, 'Send application/json with at most one UTF-8 charset.')
    length = values.get('content-length', ['0'])[0]
    _require(re.fullmatch(CONTENT_LENGTH_PATTERN, length) is not None, 400, 'Invalid content length.')
    _require(mutation or int(length) == 0, 400, 'This request must not have a body.')
    return int(length)
