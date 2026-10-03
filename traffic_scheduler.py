"""Bounded, exact-key work sharing. No evaluator or transport semantics live here.

All ownership transitions linearize under one condition lock. A detached running
job keeps its lane until its evaluator returns; cancellation never kills a JVM.
Keys are exact strings/tuples, not content hashes. Results are stored as immutable
JSON bytes and decoded separately for each caller.
"""
from collections import OrderedDict
from dataclasses import dataclass, field
import json
import secrets
import threading
import time


class CapacityError(Exception):
    pass


class Superseded(Exception):
    pass


class ChannelExpired(Exception):
    pass


def encode(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'), sort_keys=True).encode('utf-8')


class ResultCache(OrderedDict):
    """Bounded encoded results; operations must hold the scheduler lock."""
    def __init__(self, count, budget, ttl=120, clock=time.monotonic):
        super().__init__()
        self.limit, self.budget, self.ttl, self.clock = count, budget, ttl, clock
        self.bytes = 0

    def __setitem__(self, key, value):
        data = encode(value)
        size = len(data) + len(encode(key))
        if key in self:
            self.__delitem__(key)
        if size > self.budget:
            return
        while self and (len(self) >= self.limit or self.bytes + size > self.budget):
            self.popitem(last=False)
        super().__setitem__(key, (self.clock(), data, size))
        self.bytes += size

    def __getitem__(self, key):
        when, data, _ = super().__getitem__(key)
        if self.clock() - when >= self.ttl:
            self.__delitem__(key)
            raise KeyError(key)
        self.move_to_end(key)
        return json.loads(data)

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    def __delitem__(self, key):
        self.bytes -= super().__getitem__(key)[2]
        super().__delitem__(key)

    def popitem(self, last=True):
        if not self:
            raise KeyError('empty cache')
        key = next(reversed(self) if last else iter(self))
        when, data, size = super().__getitem__(key)
        self.__delitem__(key)
        return key, json.loads(data)

    def clear(self):
        super().clear()
        self.bytes = 0


@dataclass
class Subscriber:
    channel: str
    revision: int
    event: threading.Event = field(default_factory=threading.Event)
    result: bytes = None
    error: Exception = None


@dataclass
class Job:
    key: tuple
    lane: str
    turn: str
    compute: object
    size: int
    queued_at: float
    cacheable: bool
    subscribers: dict = field(default_factory=dict)
    running: bool = False


class Scheduler:
    def __init__(self, lanes=None, *, clock=time.monotonic, max_jobs=32,
                 max_subscribers=64, input_bytes=16 * 1048576, queue_seconds=5):
        self.clock = clock
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.max_jobs, self.max_subscribers, self.input_limit = max_jobs, max_subscribers, input_bytes
        self.queue_seconds = queue_seconds
        self.jobs, self.channels = {}, OrderedDict()
        self.input_bytes = self.subscribers = self.sequence = 0
        self.stopped = False
        self.caches = {'feedback': ResultCache(128, 16 * 1048576, clock=clock),
                       'behavior': ResultCache(32, 16 * 1048576, clock=clock)}
        self.counts = dict(computations=0, joined=0, cacheHits=0, superseded=0, rejected=0)
        self.queues = {lane: OrderedDict() for lane in (lanes or {'feedback': 2, 'behavior': 1})}
        self.threads = []
        for lane, count in (lanes or {'feedback': 2, 'behavior': 1}).items():
            for index in range(count):
                thread = threading.Thread(target=self._worker, args=(lane,),
                                          name=f'alloy-{lane}-{index}', daemon=True)
                thread.start()
                self.threads.append(thread)

    def _expire_channels(self):
        now = self.clock()
        for token, channel in list(self.channels.items()):
            if now - channel['seen'] >= 900:
                self._detach(token, 2**53)
                del self.channels[token]

    def issue_channel(self, peer):
        with self.condition:
            self._expire_channels()
            if (self.stopped or len(self.channels) >= 512
                    or sum(c['peer'] == peer for c in self.channels.values()) >= 32):
                raise CapacityError()
            token = secrets.token_urlsafe(32)
            self.channels[token] = dict(peer=peer, revision=0, identity=None, seen=self.clock())
            return token

    def observe(self, token, revision, identity=None):
        """Capability owns supersession; HTTP origins/revision numbers do not."""
        if token is None:
            return
        with self.condition:
            self._expire_channels()
            channel = self.channels.get(token)
            if channel is None:
                raise ChannelExpired()
            if revision < channel['revision']:
                raise Superseded()
            if revision > channel['revision']:
                self._detach(token, revision)
                channel['revision'], channel['identity'] = revision, None
            if identity is not None:
                if channel['identity'] is not None and channel['identity'] != identity:
                    raise Superseded()
                channel['identity'] = identity
            channel['seen'] = self.clock()
            self.channels.move_to_end(token)

    def current(self, token, revision):
        if token is None:
            return True
        channel = self.channels.get(token)
        return channel is not None and channel['revision'] == revision

    def _detach(self, token, revision):
        for job in list(self.jobs.values()):
            for identifier, subscriber in list(job.subscribers.items()):
                if subscriber.channel == token and subscriber.revision < revision:
                    self._finish_subscriber(job, identifier, error=Superseded())
                    self.counts['superseded'] += 1
            if not job.subscribers and not job.running:
                self._remove(job, preserve_turn=True)
        self.condition.notify_all()

    def _finish_subscriber(self, job, identifier, result=None, error=None):
        subscriber = job.subscribers.pop(identifier)
        self.subscribers -= 1
        subscriber.result, subscriber.error = result, error
        subscriber.event.set()

    def _remove(self, job, preserve_turn=False):
        self.jobs.pop(job.key, None)
        self.input_bytes -= job.size
        turns = self.queues[job.lane]
        if job.turn in turns:
            turns[job.turn] = [key for key in turns[job.turn] if key != job.key]
            if not turns[job.turn] and not preserve_turn:
                del turns[job.turn]

    def run(self, lane, key, compute, *, channel=None, revision=0, timeout=45, cacheable=True):
        with self.condition:
            if self.stopped:
                raise CapacityError()
            if not self.current(channel, revision):
                raise Superseded()
            if self.subscribers >= self.max_subscribers:
                self.counts['rejected'] += 1
                raise CapacityError()
            cached = self.caches[lane].get(key) if cacheable and lane in self.caches else None
            if cached is not None:
                self.counts['cacheHits'] += 1
                return cached
            self.sequence += 1
            identifier = self.sequence
            subscriber = Subscriber(channel, revision)
            job = self.jobs.get(key)
            if job is None:
                size = len(encode(key))
                if len(self.jobs) >= self.max_jobs or self.input_bytes + size > self.input_limit:
                    self.counts['rejected'] += 1
                    raise CapacityError()
                turn = channel or 'request-' + str(identifier)
                job = Job(key, lane, turn, compute, size, self.clock(), cacheable)
                self.jobs[key] = job
                self.input_bytes += size
                self.queues[lane].setdefault(turn, []).append(key)
            else:
                self.counts['joined'] += 1
            job.subscribers[identifier] = subscriber
            self.subscribers += 1
            self.condition.notify_all()
        if not subscriber.event.wait(timeout + self.queue_seconds + 1):
            with self.condition:
                if identifier in job.subscribers:
                    self._finish_subscriber(job, identifier, error=TimeoutError())
                    if not job.subscribers and not job.running:
                        self._remove(job)
        if subscriber.error is not None:
            raise subscriber.error
        if subscriber.result is None:
            raise TimeoutError()
        return json.loads(subscriber.result)

    def _worker(self, lane):
        while True:
            with self.condition:
                job = None
                while job is None:
                    if self.stopped:
                        return
                    turns = self.queues[lane]
                    for turn in list(turns):
                        keys = turns[turn]
                        while keys and keys[0] not in self.jobs:
                            keys.pop(0)
                        if not keys:
                            # Empty replacement turns only retain their place until
                            # the dispatcher visits; they cannot accumulate forever.
                            del turns[turn]
                            continue
                        candidate = self.jobs[keys.pop(0)]
                        if keys:
                            turns.move_to_end(turn)
                        else:
                            del turns[turn]
                        if self.clock() - candidate.queued_at >= self.queue_seconds:
                            for identifier in list(candidate.subscribers):
                                self._finish_subscriber(candidate, identifier, error=CapacityError())
                            self._remove(candidate)
                            continue
                        if candidate.subscribers:
                            job = candidate
                            job.running = True
                            self.counts['computations'] += 1
                            break
                    if job is None:
                        self.condition.wait(0.25)
            result, failure = None, None
            try:
                result = encode(job.compute())
                if len(result) > 4 * 1048576:
                    raise ValueError('Result exceeds retained evidence cap')
            except Exception:
                failure = RuntimeError('Analysis service unavailable')
            with self.condition:
                decoded = json.loads(result) if failure is None else None
                if (job.cacheable and lane in self.caches and decoded is not None
                        and decoded.get('status') in ('ok', 'invalid')):
                    self.caches[lane][job.key] = decoded
                for identifier in list(job.subscribers):
                    self._finish_subscriber(job, identifier, result, failure)
                self._remove(job)
                self.condition.notify_all()

    def stats(self):
        with self.condition:
            return dict(self.counts, jobs=len(self.jobs), running=sum(j.running for j in self.jobs.values()),
                        subscribers=self.subscribers, inputBytes=self.input_bytes, channels=len(self.channels),
                        cacheBytes=sum(c.bytes for c in self.caches.values()))

    def close(self):
        with self.condition:
            self.stopped = True
            for job in list(self.jobs.values()):
                for identifier in list(job.subscribers):
                    self._finish_subscriber(job, identifier, error=Superseded())
                if not job.running:
                    self._remove(job)
            self.condition.notify_all()
        for thread in self.threads:
            thread.join(timeout=1)


class EvidenceStore:
    """Expiring bearer pins survive result-cache eviction, with a fixed byte cap."""
    def __init__(self, *, maximum=128, budget=32 * 1048576, ttl=120, clock=time.monotonic):
        self.maximum, self.budget, self.ttl, self.clock = maximum, budget, ttl, clock
        self.lock = threading.Lock()
        self.entries = OrderedDict()
        self.bytes = 0

    def _cleanup(self):
        now = self.clock()
        for token, (created, identity, data, size) in list(self.entries.items()):
            if now - created >= self.ttl:
                del self.entries[token]
                self.bytes -= size

    def pin(self, identity, value, token=None):
        data = encode(value)
        size = len(data) + len(encode(identity))
        with self.lock:
            self._cleanup()
            for existing, (_, context, content, _) in self.entries.items():
                if context == identity and content == data:
                    return existing
            if len(self.entries) >= self.maximum or self.bytes + size > self.budget:
                return None  # explicit expiry/unavailability, never another solve
            token = token or secrets.token_hex(32)
            if token in self.entries:
                return None
            self.entries[token] = (self.clock(), identity, data, size)
            self.bytes += size
            return token

    def get(self, token, identity):
        with self.lock:
            self._cleanup()
            entry = self.entries.get(token)
            if entry is None or entry[1] != identity:
                return None
            return json.loads(entry[2])

    def find(self, identity, matches):
        with self.lock:
            self._cleanup()
            for _, context, data, _ in self.entries.values():
                if context == identity:
                    value = json.loads(data)
                    if matches(value):
                        return value
            return None
