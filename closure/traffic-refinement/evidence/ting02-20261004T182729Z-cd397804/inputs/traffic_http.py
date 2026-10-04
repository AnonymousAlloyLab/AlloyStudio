"""Bounded HTTP admission, byte reads and public projection revalidation.

Reservations precede thread creation. The optional control listener has a
separate loopback-only envelope; forwarded headers never choose quota identity.
"""
from collections import OrderedDict
import hashlib
from http.server import ThreadingHTTPServer
import ipaddress
import json
import re
import socket
import sys
import threading
import time
from traffic_profile import TrafficProfile, normalized_profile, initial_admission


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

    @classmethod
    def from_initial(cls, state):
        bucket = cls.__new__(cls)
        bucket.capacity, bucket.rate = state['capacity'], state['rate']
        bucket.credit, bucket.last = state['credit'], state['last']
        return bucket

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
        profile, state = initial_admission(profile, control, clock)
        self.profile, self.control, self.clock = profile, control, clock
        self.lock = threading.Lock()
        self.active, self.peak = state['active'], state['peak']
        self.accepted, self.rejected = state['accepted'], state['rejected']
        self.owners, self.anonymous_owners = state['owners'], state['anonymous_owners']
        self.limit = state['limit']
        self.bucket = TokenBucket.from_initial(state)
        self.peers = state['peers']

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
        profile = normalized_profile(TrafficProfile() if traffic_profile is None else traffic_profile)
        if type(control) is not bool:
            raise ValueError('The control lane selector must be a Boolean.')
        self.traffic_profile = profile
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
        self._request_threads_lock = threading.Lock()
        self._request_threads = {}
        super().__init__(address, handler)

    def _reap_request_threads(self):
        # Retain the socket reservation through native thread termination.
        # A handler's finally block is too early: its thread may still be alive.
        with self._request_threads_lock:
            for request, thread in list(self._request_threads.items()):
                # ident can be published before bootstrap marks the thread
                # started; is_alive() is still false at that intermediate cut.
                # A zero-time join refuses that state (and the current thread).
                try:
                    thread.join(timeout=0)
                except RuntimeError:
                    continue
                if not thread.is_alive():
                    del self._request_threads[request]
                    self.http_admission.release(request)

    def service_actions(self):
        self._reap_request_threads()

    def process_request(self, request, client_address):
        self._reap_request_threads()
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
        with self._request_threads_lock:
            thread = None
            start_attempted = False
            try:
                thread = threading.Thread(target=self.process_request_thread,
                                          args=(request, client_address))
                thread.daemon = self.daemon_threads
                self._request_threads[request] = thread
                start_attempted = True
                thread.start()
            except BaseException:
                # start() can be interrupted after native launch but before
                # ident is published. An attempted start retains its slot even
                # if bootstrap never becomes observable; do not guess it failed.
                if not start_attempted:
                    self._request_threads.pop(request, None)
                    self.http_admission.release(request)
                raise

    def handle_error(self, request, client_address):
        # socketserver's default traceback can expose private exception text,
        # payloads or server paths. One constant diagnostic is enough to locate
        # the operational failure without copying any request-owned value.
        try:
            sys.stderr.write('Alloy HTTP handler failed.\n')
        except (OSError, ValueError):
            pass

    def traffic_stats(self):
        self._reap_request_threads()
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
        now = self.check_deadline()
        self.deadline = now + self.profile.body_seconds
        self.headers_mode = False

    def check_deadline(self):
        now = self.clock()
        if now >= self.deadline:
            raise TimeoutError('HTTP read deadline exceeded.')
        return now

    def _recv(self, count):
        self.check_deadline()
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise TimeoutError('HTTP read deadline exceeded.')
        self.connection.settimeout(min(remaining, self.profile.idle_seconds))
        chunk = self.connection.recv(min(count, 4096))
        self.check_deadline()
        return chunk

    def readline(self, limit=-1):
        self.check_deadline()
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
        self.check_deadline()
        return result

    def read1(self, count):
        self.check_deadline()
        if count <= 0:
            result = b''
        elif self.buffer:
            result = bytes(self.buffer[:count])
            del self.buffer[:count]
        else:
            result = self._recv(count)
        self.check_deadline()
        return result

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
