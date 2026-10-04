"""Bounded HTTP admission, byte reads and public projection revalidation.

Reservations precede thread creation. The optional control listener has a
separate loopback-only envelope; forwarded headers never choose quota identity.
"""
from collections import OrderedDict
from dataclasses import dataclass, fields
import hashlib
from http.server import ThreadingHTTPServer
import ipaddress
import json
import re
import socket
import sys
import threading
import time
from traffic_limits import validated_int, validated_seconds


@dataclass(frozen=True)
class TrafficProfile:
    public_handlers: int = 30
    control_handlers: int = 2
    public_burst: int = 60
    public_rate: int = 30
    control_burst: int = 4
    control_rate: int = 2
    peer_burst: int = 60
    peer_rate: int = 30
    peer_entries: int = 1024
    peer_idle_seconds: int = 120
    backlog: int = 32
    control_backlog: int = 2
    line_bytes: int = 8192
    header_bytes: int = 32768
    header_count: int = 64
    header_seconds: float = 5.0
    body_seconds: float = 5.0
    write_seconds: float = 5.0
    idle_seconds: float = 5.0
    json_depth: int = 32
    response_bytes: int = 8 * 1024 * 1024
    public_cache_bytes: int = 16 * 1024 * 1024
    public_cache_entries: int = 512

    def __post_init__(self):
        values = {
            'public_handlers': self.public_handlers,
            'control_handlers': self.control_handlers,
            'public_burst': self.public_burst,
            'public_rate': self.public_rate,
            'control_burst': self.control_burst,
            'control_rate': self.control_rate,
            'peer_burst': self.peer_burst,
            'peer_rate': self.peer_rate,
            'peer_entries': self.peer_entries,
            'peer_idle_seconds': self.peer_idle_seconds,
            'backlog': self.backlog,
            'control_backlog': self.control_backlog,
            'line_bytes': self.line_bytes,
            'header_bytes': self.header_bytes,
            'header_count': self.header_count,
            'header_seconds': self.header_seconds,
            'body_seconds': self.body_seconds,
            'write_seconds': self.write_seconds,
            'idle_seconds': self.idle_seconds,
            'json_depth': self.json_depth,
            'response_bytes': self.response_bytes,
            'public_cache_bytes': self.public_cache_bytes,
            'public_cache_entries': self.public_cache_entries,
        }
        if {field.name for field in fields(self)} != set(values):
            raise ValueError('Unregistered HTTP profile field.')
        for name, value in values.items():
            if name.endswith('_seconds') and name != 'peer_idle_seconds':
                try:
                    validated_seconds(value, maximum=300)
                except ValueError:
                    raise ValueError('HTTP deadlines must be positive, finite and at most 300 seconds.') from None
            else:
                try:
                    validated_int(value)
                except ValueError:
                    raise ValueError('HTTP bounds must be positive, finite integers.') from None
        if self.line_bytes > self.header_bytes or self.header_count > 100:
            raise ValueError('Invalid HTTP header profile.')
        if self.public_handlers + self.control_handlers > 256:
            raise ValueError('HTTP handler profile exceeds the supported envelope.')


class HTTPInputError(ValueError):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


class TokenBucket:
    """Integer nanocredits; backward clock observations never create tokens."""
    UNIT = 1_000_000_000

    def __init__(self, capacity, rate, now):
        self.capacity, self.rate = capacity * self.UNIT, rate
        self.credit, self.last = self.capacity, now

    def refill(self, now):
        now = max(now, self.last)
        self.credit = min(self.capacity, self.credit + (now - self.last) * self.rate)
        self.last = now

    def available(self):
        return self.credit >= self.UNIT

    def charge(self):
        self.credit -= self.UNIT


class Admission:
    """Atomic count and total/source credit ownership for one listener."""
    def __init__(self, profile, *, control=False, clock=time.monotonic_ns):
        self.profile, self.control, self.clock = profile, control, clock
        self.lock = threading.Lock()
        self.active = self.peak = self.accepted = self.rejected = 0
        self.owners, self.anonymous_owners = set(), []
        self.limit = profile.control_handlers if control else profile.public_handlers
        self.bucket = TokenBucket(profile.control_burst if control else profile.public_burst,
                                  profile.control_rate if control else profile.public_rate, clock())
        self.peers = OrderedDict()

    def reserve(self, peer, *, owner=None):
        with self.lock:
            if owner is not None and owner in self.owners:
                raise ValueError('HTTP connection already owns a reservation.')
            now = self.clock()
            self.bucket.refill(now)
            if self.active >= self.limit:
                self.rejected += 1
                return 503
            if not self.bucket.available():
                self.rejected += 1
                return 429
            if not self.control:
                stale = []
                for identity, (bucket, seen) in self.peers.items():
                    if now - seen < self.profile.peer_idle_seconds * TokenBucket.UNIT:
                        break
                    bucket.refill(now)
                    if bucket.credit == bucket.capacity:
                        stale.append(identity)
                for identity in stale:
                    self.peers.pop(identity)
                pair = self.peers.get(peer)
                if pair is None:
                    if len(self.peers) >= self.profile.peer_entries:
                        self.rejected += 1
                        return 429
                    pair = (TokenBucket(self.profile.peer_burst, self.profile.peer_rate, now), now)
                    self.peers[peer] = pair
                bucket = pair[0]
                bucket.refill(now)
                self.peers[peer] = (bucket, max(now, pair[1]))
                self.peers.move_to_end(peer)
                if not bucket.available():
                    self.rejected += 1
                    return 429
                bucket.charge()
            self.bucket.charge()
            if owner is None:
                owner = object()
                self.anonymous_owners.append(owner)
            self.owners.add(owner)
            self.active += 1
            self.accepted += 1
            self.peak = max(self.peak, self.active)
            return None

    def release(self, owner=None):
        with self.lock:
            if owner is None:
                if not self.anonymous_owners:
                    return
                owner = self.anonymous_owners.pop()
            if owner in self.owners:
                self.owners.remove(owner)
                self.active -= 1

    def statistics(self):
        with self.lock:
            return {'activeHandlers': self.active, 'peakHandlers': self.peak,
                    'acceptedConnections': self.accepted, 'rejectedConnections': self.rejected,
                    'peerEntries': len(self.peers), 'handlerLimit': self.limit}


class PublicViews:
    """A bounded cache containing only serialized allowlisted public bytes."""
    def __init__(self, profile):
        self.profile, self.lock = profile, threading.Lock()
        self.generation = None
        self.entries, self.bytes = OrderedDict(), 0

    def get(self, generation, key, produce):
        with self.lock:
            if self.generation is not generation:
                self.generation = generation  # retain identity, preventing Python id reuse
                self.entries.clear()
                self.bytes = 0
            if key in self.entries:
                self.entries.move_to_end(key)
                return self.entries[key]
            value = produce()
            if not isinstance(value, bytes):
                value = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
            if len(value) > self.profile.response_bytes:
                raise HTTPInputError(503, 'Public response exceeds its byte limit.')
            result = (value, '"' + hashlib.sha256(value).hexdigest() + '"')
            if len(value) <= self.profile.public_cache_bytes:
                while self.entries and (len(self.entries) >= self.profile.public_cache_entries
                        or self.bytes + len(value) > self.profile.public_cache_bytes):
                    _, old = self.entries.popitem(last=False)
                    self.bytes -= len(old[0])
                self.entries[key] = result
                self.bytes += len(value)
            return result


class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, *, traffic_profile=None, control=False, shared_app=None):
        self.traffic_profile = traffic_profile or TrafficProfile()
        self.control_listener = control
        self.shared_app = shared_app
        if control:
            try:
                if not ipaddress.ip_address(address[0]).is_loopback:
                    raise ValueError()
            except ValueError:
                raise ValueError('Control listener must bind a literal loopback address.') from None
        self.request_queue_size = (self.traffic_profile.control_backlog if control
                                   else self.traffic_profile.backlog)
        self.http_admission = Admission(self.traffic_profile, control=control)
        self.http_public_views = PublicViews(self.traffic_profile)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        rejection = self.http_admission.reserve(client_address[0], owner=request)
        if rejection is not None:
            # No thread, request parser or pending queue is allocated for rejection.
            body = b'{"status":"busy","retryable":true,"dispatched":false,"code":"capacity"}'
            reason = b'Too Many Requests' if rejection == 429 else b'Service Unavailable'
            packet = (b'HTTP/1.1 ' + str(rejection).encode() + b' ' + reason
                      + b'\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n'
                      + b'Connection: close\r\nRetry-After: 1\r\nContent-Length: '
                      + str(len(body)).encode() + b'\r\n\r\n' + body)
            try:
                request.setblocking(False)
                request.send(packet)
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.http_admission.release(request)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.http_admission.release(request)

    def handle_error(self, request, client_address):
        # socketserver's default traceback can expose private exception text,
        # payloads or server paths. One constant diagnostic is enough to locate
        # the operational failure without copying any request-owned value.
        try:
            sys.stderr.write('Alloy HTTP handler failed.\n')
        except (OSError, ValueError):
            pass

    def traffic_stats(self):
        return self.http_admission.statistics()


class DeadlineReader:
    """Small explicit socket buffer with an absolute deadline on every recv.

    Socket.makefile().readline() can keep receiving trickled bytes indefinitely
    under an inactivity timeout. Here each raw recv observes the same deadline.
    """
    def __init__(self, connection, profile, *, clock=time.monotonic):
        self.connection, self.profile, self.clock = connection, profile, clock
        self.buffer = bytearray()
        self.closed = False
        self.begin_headers()

    def begin_headers(self):
        self.deadline = self.clock() + self.profile.header_seconds
        self.header_remaining = self.profile.header_bytes
        self.header_lines = 0
        self.headers_mode = True

    def begin_body(self):
        self.deadline = self.clock() + self.profile.body_seconds
        self.headers_mode = False

    def _recv(self, count):
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise TimeoutError('HTTP read deadline exceeded.')
        self.connection.settimeout(min(remaining, self.profile.idle_seconds))
        chunk = self.connection.recv(min(count, 4096))
        if self.clock() >= self.deadline:
            raise TimeoutError('HTTP read deadline exceeded.')
        return chunk

    def readline(self, limit=-1):
        limit = min(limit if limit >= 0 else self.profile.line_bytes + 1,
                    self.profile.line_bytes + 1)
        while True:
            newline = self.buffer.find(b'\n')
            count = min(newline + 1 if newline >= 0 else len(self.buffer), limit)
            if newline >= 0 or len(self.buffer) >= limit:
                result = bytes(self.buffer[:count])
                del self.buffer[:count]
                break
            chunk = self._recv(min(4096, limit - len(self.buffer)))
            if not chunk:
                result = bytes(self.buffer)
                self.buffer.clear()
                break
            self.buffer.extend(chunk)
        if self.headers_mode:
            self.header_remaining -= len(result)
            self.header_lines += 1
            if (len(result) > self.profile.line_bytes or self.header_remaining < 0
                    or self.header_lines > self.profile.header_count + 2):
                raise HTTPInputError(431, 'Request headers exceed their limit.')
            # Reject obsolete folding before the stdlib parser can merge it.
            if self.header_lines > 1 and result[:1] in (b' ', b'\t'):
                raise HTTPInputError(400, 'Folded request headers are not supported.')
            if result and not result.endswith(b'\r\n'):
                raise HTTPInputError(400, 'Request lines must use CRLF.')
            if self.header_lines > 1 and result != b'\r\n':
                name, colon, value = result[:-2].partition(b':')
                if (not colon or re.fullmatch(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) is None
                        or any(byte < 32 and byte != 9 or byte == 127 for byte in value)):
                    raise HTTPInputError(400, 'Malformed request header.')
        return result

    def read1(self, count):
        if count <= 0:
            return b''
        if self.clock() >= self.deadline:
            raise TimeoutError('HTTP read deadline exceeded.')
        if self.buffer:
            result = bytes(self.buffer[:count])
            del self.buffer[:count]
            return result
        return self._recv(count)

    def read(self, count=-1):
        if count < 0:
            raise ValueError('Unbounded HTTP reads are forbidden.')
        chunks = []
        while count:
            chunk = self.read1(count)
            if not chunk:
                break
            chunks.append(chunk)
            count -= len(chunk)
        return b''.join(chunks)

    def close(self):
        self.closed = True
        self.buffer.clear()


def bounded_json(raw, depth_limit=32):
    """UTF-8 only, no duplicate keys/nonfinite numbers or excessive nesting."""
    try:
        text = raw.decode('utf-8')
        depth, quoted, escaped = 0, False, False
        for character in text:
            if quoted:
                if escaped:
                    escaped = False
                elif character == '\\':
                    escaped = True
                elif character == '"':
                    quoted = False
            elif character == '"':
                quoted = True
            elif character in '[{':
                depth += 1
                if depth > depth_limit:
                    raise ValueError()
            elif character in ']}':
                depth -= 1
        def object_(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError()
                result[key] = value
            return result
        def invalid(_):
            raise ValueError()
        value = json.loads(text, object_pairs_hook=object_, parse_constant=invalid)
        # This also excludes lone Unicode surrogates and overflowing exponents.
        json.dumps(value, ensure_ascii=False, allow_nan=False).encode('utf-8')
        if type(value) is not dict:
            raise ValueError()
        return value
    except (UnicodeError, ValueError, TypeError, RecursionError, OverflowError):
        raise HTTPInputError(400, 'Invalid, incomplete or timed-out JSON request.') from None
