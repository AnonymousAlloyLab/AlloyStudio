"""Private administrator configuration and bounded, revocable browser sessions.

HTTP body framing and deadlines belong to the handler. This module never reads
request bodies and never trusts forwarding headers for browser-origin authority.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import stat
import threading
import time
from urllib.parse import urlsplit


CONFIG_NAME = 'admin.local.json'
SCRYPT = {'n': 131072, 'r': 8, 'p': 1, 'dklen': 32, 'maxmem': 256 * 1024 * 1024}
PREAUTH_TTL, ABSOLUTE_TTL, IDLE_TTL = 300, 1800, 900
PREAUTH_CAP, SESSION_CAP = 64, 16
TOKEN = re.compile(r'[A-Za-z0-9_-]{43}')


class AuthError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


@dataclass(frozen=True)
class Principal:
    owner: str
    generation: str
    authenticated: bool


@dataclass(frozen=True)
class Settings:
    origin: str
    base_path: str
    generation: str
    secure: bool
    preauth_cookie: str
    session_cookie: str


@dataclass
class _Session:
    created: float
    touched: float
    generation: str


def _loopback(host):
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def normalize_origin(value):
    if type(value) is not str or not value or not value.isascii() or re.search(r'\s', value):
        raise ValueError('Invalid administrator origin.')
    try:
        parsed = urlsplit(value)
        host, port = parsed.hostname, parsed.port
        if (parsed.scheme not in ('http', 'https') or not host or parsed.username is not None
                or parsed.password is not None or parsed.path or parsed.query or parsed.fragment
                or '?' in value or '#' in value or parsed.netloc.endswith(':')
                or not re.fullmatch(r'[A-Za-z0-9.:[\]-]+', parsed.netloc)):
            raise ValueError
        if ':' in host:
            host = ipaddress.IPv6Address(host).compressed
        if parsed.scheme == 'http' and not _loopback(host):
            raise ValueError
        authority = '[' + host + ']' if ':' in host else host
        if port is not None and port != (443 if parsed.scheme == 'https' else 80):
            authority += ':' + str(port)
        return parsed.scheme + '://' + authority
    except (TypeError, ValueError):
        raise ValueError('Use HTTPS, or an explicit loopback HTTP origin.') from None


def normalize_base_path(value):
    if (type(value) is not str or not value.startswith('/') or
            not re.fullmatch(r'/[A-Za-z0-9._~/-]*', value) or '//' in value):
        raise ValueError('Invalid administrator application path.')
    if any(part in ('.', '..') for part in value.split('/')):
        raise ValueError('Invalid administrator application path.')
    return value if value.endswith('/') else value + '/'


def password_bytes(password):
    try:
        if type(password) is not str or '\0' in password:
            raise ValueError
        encoded = password.encode('utf-8')
        if not 12 <= len(encoded) <= 1024:
            raise ValueError
        return encoded
    except (ValueError, UnicodeError):
        raise ValueError('Password must contain 12–1024 valid UTF-8 bytes without NUL.') from None


def derive_password(password, salt):
    return hashlib.scrypt(password_bytes(password), salt=salt, **SCRYPT)


def _safe_path(path):
    path = Path(path).absolute()
    for component in (*reversed(path.parents), path):
        try:
            details = component.lstat()
        except FileNotFoundError:
            continue
        if (stat.S_ISLNK(details.st_mode) or
                getattr(details, 'st_file_attributes', 0) & 0x400):
            raise ValueError('Administrator configuration paths must not contain links.')
    return path


def configuration(origin, base_path, password):
    salt = secrets.token_bytes(16)
    return {'schemaVersion': 1, 'origin': normalize_origin(origin),
            'basePath': normalize_base_path(base_path),
            'password': {'algorithm': 'scrypt', 'n': SCRYPT['n'], 'r': SCRYPT['r'],
                         'p': SCRYPT['p'], 'dklen': SCRYPT['dklen'],
                         'salt': salt.hex(), 'hash': derive_password(password, salt).hex()}}


def _unique_json(source):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate private configuration field.')
            result[key] = value
        return result
    return json.loads(source.decode('utf-8'), object_pairs_hook=pairs)


def _load_configuration(root):
    path = _safe_path(Path(root) / CONFIG_NAME)
    details = path.stat()
    if (not stat.S_ISREG(details.st_mode) or details.st_size > 8192 or
            os.name != 'nt' and details.st_mode & 0o077):
        raise ValueError('Invalid private administrator configuration.')
    with path.open('rb') as source:
        contents = source.read(8193)
    if len(contents) > 8192:
        raise ValueError('Invalid private administrator configuration.')
    data = _unique_json(contents)
    if type(data) is not dict or set(data) != {'schemaVersion', 'origin', 'basePath', 'password'}:
        raise ValueError('Invalid private administrator configuration.')
    if type(data['schemaVersion']) is not int or data['schemaVersion'] != 1:
        raise ValueError('Unsupported administrator configuration.')
    origin, base = normalize_origin(data['origin']), normalize_base_path(data['basePath'])
    if origin != data['origin'] or base != data['basePath']:
        raise ValueError('Noncanonical administrator configuration.')
    record = data['password']
    if type(record) is not dict or set(record) != {'algorithm', 'n', 'r', 'p', 'dklen', 'salt', 'hash'}:
        raise ValueError('Invalid private administrator configuration.')
    if record['algorithm'] != 'scrypt' or any(type(record[key]) is not int or record[key] != SCRYPT[key]
                                            for key in ('n', 'r', 'p', 'dklen')):
        raise ValueError('Unsupported administrator password parameters.')
    if (type(record['salt']) is not str or re.fullmatch('[a-f0-9]{32}', record['salt']) is None or
            type(record['hash']) is not str or re.fullmatch('[a-f0-9]{64}', record['hash']) is None):
        raise ValueError('Invalid administrator password verifier.')
    return data, hashlib.sha256(contents).hexdigest()


def _header_values(headers, name):
    if hasattr(headers, 'get_all'):
        return headers.get_all(name, [])
    return [value for key, value in headers.items() if key.lower() == name.lower()]


def _one_header(headers, name, *, required=False):
    values = _header_values(headers, name)
    if len(values) > 1 or required and len(values) != 1 or any(type(value) is not str for value in values):
        raise AuthError(403, 'Invalid administrator request headers.')
    return values[0] if values else None


class AuthManager:
    def __init__(self, root, *, clock=time.monotonic):
        self.root, self.clock = Path(root), clock
        self.lock = threading.RLock()
        self._kdf = threading.BoundedSemaphore(1)
        self._preauth, self._sessions = {}, {}
        self._failures = []
        self._generation, self._config, self._settings = None, None, None
        self._csrf_secret = secrets.token_bytes(32)

    def _refresh(self):
        try:
            config, generation = _load_configuration(self.root)
        except (OSError, ValueError, TypeError, RecursionError, UnicodeError):
            config, generation = None, None
        if generation != self._generation:
            self._preauth.clear()
            self._sessions.clear()
            self._failures.clear()
            self._generation = generation
        self._config = config
        if config is None:
            self._settings = None
            return
        origin, base = config['origin'], config['basePath']
        suffix = hashlib.sha256((origin + '\n' + base).encode()).hexdigest()[:16]
        secure = origin.startswith('https://')
        prefix = '__Host-AlloyAdmin-' if secure else 'AlloyAdminLocal-'
        self._settings = Settings(origin, base, generation, secure, prefix + suffix + '-pre', prefix + suffix + '-session')

    @property
    def settings(self):
        with self.lock:
            self._refresh()
            return self._settings

    def _active(self):
        self._refresh()
        if self._settings is None:
            raise AuthError(404, 'Administration is disabled.')
        now = self.clock()
        for owner, record in list(self._preauth.items()):
            if now - record.created >= PREAUTH_TTL:
                del self._preauth[owner]
        for owner, record in list(self._sessions.items()):
            if now - record.created >= ABSOLUTE_TTL or now - record.touched >= IDLE_TTL:
                del self._sessions[owner]
        self._failures = [instant for instant in self._failures if now - instant < 60]
        return self._settings, now

    def _request(self, headers, peer, mutation):
        settings, now = self._active()
        origin = _one_header(headers, 'Origin', required=mutation)
        if origin is not None and origin != settings.origin:
            raise AuthError(403, 'Administrator origin was rejected.')
        site = _one_header(headers, 'Sec-Fetch-Site')
        if site is not None and site not in ('same-origin', 'none'):
            raise AuthError(403, 'Cross-site administrator requests are not allowed.')
        if not settings.secure:
            host = _one_header(headers, 'Host', required=True)
            address = peer[0] if isinstance(peer, tuple) else peer
            try:
                local_peer = ipaddress.ip_address(address).is_loopback
            except ValueError:
                local_peer = False
            if not local_peer or host != urlsplit(settings.origin).netloc:
                raise AuthError(403, 'HTTP administration is available only on loopback.')
        # The handler owns all message framing; duplicates here also fail before
        # its body reader when authorize/bootstrap is used as the first gate.
        _one_header(headers, 'Content-Length')
        if _header_values(headers, 'Transfer-Encoding'):
            raise AuthError(400, 'Transfer encoding is not supported.')
        return settings, now

    def _cookie_owner(self, headers, name):
        values = []
        for header in _header_values(headers, 'Cookie'):
            if type(header) is not str or len(header) > 8192:
                raise AuthError(403, 'Invalid administrator cookie.')
            for item in header.split(';'):
                key, separator, value = item.strip().partition('=')
                if key == name:
                    if not separator:
                        raise AuthError(403, 'Invalid administrator cookie.')
                    values.append(value)
        if len(values) > 1 or values and TOKEN.fullmatch(values[0]) is None:
            raise AuthError(403, 'Invalid administrator cookie.')
        return hashlib.sha256(values[0].encode()).hexdigest() if values else None

    def _cookies(self, headers, settings):
        return (self._cookie_owner(headers, settings.preauth_cookie),
                self._cookie_owner(headers, settings.session_cookie))

    def _csrf(self, principal):
        message = f'{principal.generation}:{principal.authenticated}:{principal.owner}'.encode()
        return hmac.new(self._csrf_secret, message, hashlib.sha256).hexdigest()

    def _cookie(self, name, value, lifetime):
        security = '; Secure' if self._settings.secure else ''
        return f'{name}={value}; Path=/; Max-Age={lifetime}; HttpOnly; SameSite=Strict{security}'

    def _state(self, principal):
        return {'enabled': True, 'authenticated': principal.authenticated,
                'csrfToken': self._csrf(principal)}

    def _valid(self, principal, *, authenticated=True):
        settings, now = self._active()
        if (not isinstance(principal, Principal) or principal.generation != settings.generation
                or principal.authenticated is not authenticated):
            raise AuthError(401, 'Administrator session has expired. Sign in again.')
        store = self._sessions if authenticated else self._preauth
        record = store.get(principal.owner)
        if record is None or record.generation != settings.generation:
            raise AuthError(401, 'Administrator session has expired. Sign in again.')
        return settings, now, record

    def bootstrap(self, headers, peer):
        with self.lock:
            self._refresh()
            if self._settings is None:
                return {'enabled': False, 'authenticated': False}, []
            settings, now = self._request(headers, peer, False)
            preauth, authenticated = self._cookies(headers, settings)
            if authenticated in self._sessions:
                principal = Principal(authenticated, settings.generation, True)
                self._sessions[authenticated].touched = now
                return self._state(principal), []
            if preauth in self._preauth:
                return self._state(Principal(preauth, settings.generation, False)), []
            if len(self._preauth) >= PREAUTH_CAP:
                raise AuthError(429, 'Administrator sign-in is busy. Retry shortly.')
            token = secrets.token_urlsafe(32)
            owner = hashlib.sha256(token.encode()).hexdigest()
            principal = Principal(owner, settings.generation, False)
            self._preauth[owner] = _Session(now, now, settings.generation)
            return self._state(principal), [self._cookie(settings.preauth_cookie, token, PREAUTH_TTL)]

    def authorize(self, headers, peer, mutation=True, preauth=False):
        with self.lock:
            settings, now = self._request(headers, peer, mutation)
            anonymous, authenticated = self._cookies(headers, settings)
            owner = anonymous if preauth else authenticated
            principal = Principal(owner, settings.generation, not preauth)
            _, _, record = self._valid(principal, authenticated=not preauth)
            if mutation:
                csrf = _one_header(headers, 'X-CSRF-Token', required=True)
                if re.fullmatch('[a-f0-9]{64}', csrf) is None or not hmac.compare_digest(csrf, self._csrf(principal)):
                    raise AuthError(403, 'Administrator request token was rejected.')
            return principal

    def validate(self, principal):
        """Validate authority without renewing idle lifetime."""
        with self.lock:
            self._valid(principal)

    def touch(self, principal):
        """Renew idle lifetime only after a handler completed a valid request."""
        with self.lock:
            _, now, record = self._valid(principal)
            record.touched = now

    def login(self, principal, password):
        try:
            password_bytes(password)
        except ValueError:
            raise AuthError(400, 'Enter a valid administrator password.') from None
        with self.lock:
            settings, now, _ = self._valid(principal, authenticated=False)
            if len(self._failures) >= 5:
                raise AuthError(429, 'Too many sign-in attempts. Retry in one minute.')
            if not self._kdf.acquire(blocking=False):
                raise AuthError(429, 'Administrator sign-in is busy. Retry shortly.')
            salt = bytes.fromhex(self._config['password']['salt'])
            expected = bytes.fromhex(self._config['password']['hash'])
        try:
            actual = derive_password(password, salt)
            with self.lock:
                settings, now, _ = self._valid(principal, authenticated=False)
                if not hmac.compare_digest(actual, expected):
                    self._failures.append(now)
                    raise AuthError(401, 'Administrator sign-in failed.')
                if len(self._sessions) >= SESSION_CAP:
                    raise AuthError(429, 'Administrator session capacity reached. Sign out another session.')
                token = secrets.token_urlsafe(32)
                owner = hashlib.sha256(token.encode()).hexdigest()
                authenticated = Principal(owner, settings.generation, True)
                self._sessions[owner] = _Session(now, now, settings.generation)
                del self._preauth[principal.owner]
                return self._state(authenticated), [self._cookie(settings.session_cookie, token, ABSOLUTE_TTL),
                                                    self._cookie(settings.preauth_cookie, '', 0)]
        except (ValueError, MemoryError):
            raise AuthError(503, 'Administrator password verification is unavailable.') from None
        finally:
            self._kdf.release()

    def logout(self, principal):
        with self.lock:
            settings, _, _ = self._valid(principal)
            del self._sessions[principal.owner]
            return [self._cookie(settings.session_cookie, '', 0), self._cookie(settings.preauth_cookie, '', 0)]

    @contextmanager
    def guard(self, principal):
        """Final authority check; caller holds this lock through its DB commit."""
        with self.lock:
            self._valid(principal)
            yield
