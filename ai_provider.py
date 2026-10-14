"""Private deployment profiles and bounded, non-fallback AI wire adapters.

The application owns prompts, response validation, transport deadlines and the
no-redirect opener. This module neither sends requests nor logs credentials.
"""
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from urllib.parse import quote, urlsplit
from urllib.request import Request

BACKEND_ROOT = Path(__file__).resolve().parent
MAX_CONFIG_BYTES = 65536
MAX_KEY_BYTES = 16384
MAX_TEXT_BYTES = 1048576
ENDPOINTS = {
    'openai': 'https://api.openai.com/v1/responses',
    'anthropic': 'https://api.anthropic.com/v1/messages',
    'gemini': 'https://generativelanguage.googleapis.com/v1beta/models/',
    'deepseek': 'https://api.deepseek.com/chat/completions',
}
EFFORTS = {
    'openai': {'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'},
    'anthropic': {'low', 'medium', 'high', 'xhigh', 'max'},
    'gemini': {'minimal', 'low', 'medium', 'high'},
    'deepseek': {'none', 'low', 'high', 'max'},
    'openai-compatible': {'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'},
}


@dataclass(frozen=True)
class Profile:
    protocol: str
    model: str
    key: str = field(repr=False)
    endpoint: str
    effort: str | None = None

    @property
    def cache_identity(self):
        """A credential change invalidates reuse without exposing the credential."""
        value = [self.protocol, self.model, self.endpoint, self.effort,
                 hashlib.sha256(self.key.encode('ascii')).hexdigest()]
        return hashlib.sha256(json.dumps(value, separators=(',', ':')).encode()).hexdigest()


def _invalid():
    return ValueError('Invalid private AI configuration or provider response')


def _ascii(value, maximum, *, empty=False):
    if (not isinstance(value, str) or len(value) > maximum
            or (not value and not empty) or not value.isascii()
            or any(not '!' <= char <= '~' for char in value)):
        raise _invalid()
    return value


def _private_text(path, limit):
    # Test permissions on the opened file, avoiding a stat/open replacement race.
    # Windows modes do not express ACLs: installed backend ACLs protect these files.
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NONBLOCK', 0)
                         | getattr(os, 'O_BINARY', 0))
    with os.fdopen(descriptor, 'rb') as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode):
            raise _invalid()
        if sys.platform != 'win32' and info.st_mode & 0o077:
            raise _invalid()
        raw = source.read(limit + 1)
    if len(raw) > limit:
        raise _invalid()
    return raw.decode('utf-8-sig').strip()


def _unique(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise _invalid()
        result[name] = value
    return result


def _path(value, directory):
    if (not isinstance(value, str) or not value or len(value.encode('utf-8')) > 4096
            or '\x00' in value):
        raise _invalid()
    path = Path(value)
    return path if path.is_absolute() else directory / path


def _endpoint(value):
    _ascii(value, 2048)
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or not parsed.path or '\\' in value):
        raise _invalid()
    # Validate malformed ports before a request can reach the transport.
    parsed.port
    return value


def _profile(spec, directory):
    if (not isinstance(spec, dict)
            or set(spec) - {'provider', 'model', 'api_key', 'api_key_file',
                            'reasoning_effort', 'endpoint'}
            or not {'provider', 'model'} <= set(spec)
            or ('api_key' in spec) == ('api_key_file' in spec)):
        raise _invalid()
    protocol = spec['provider']
    if not isinstance(protocol, str) or protocol not in EFFORTS:
        raise _invalid()
    model = _ascii(spec['model'], 200)
    effort = spec.get('reasoning_effort')
    if 'reasoning_effort' in spec and (
            not isinstance(effort, str) or effort not in EFFORTS[protocol]):
        raise _invalid()
    if protocol == 'openai-compatible':
        endpoint = _endpoint(spec.get('endpoint'))
    elif 'endpoint' in spec:
        raise _invalid()
    elif protocol == 'gemini':
        endpoint = ENDPOINTS[protocol] + quote(model, safe='') + ':generateContent'
    else:
        endpoint = ENDPOINTS[protocol]
    if 'api_key' in spec:
        key = _ascii(spec['api_key'], MAX_KEY_BYTES, empty=True)
    else:
        key = _ascii(_private_text(_path(spec['api_key_file'], directory), MAX_KEY_BYTES),
                     MAX_KEY_BYTES, empty=True)
    return Profile(protocol, model, key, endpoint, effort)


def _configured_profiles(config, directory):
    if (not isinstance(config, dict) or set(config) != {'version', 'profiles', 'roles'}
            or type(config['version']) is not int or config['version'] != 1
            or not isinstance(config['profiles'], dict)
            or not 1 <= len(config['profiles']) <= 32
            or not isinstance(config['roles'], dict)
            or set(config['roles']) - {'guidance', 'upload', 'review'}):
        raise _invalid()
    profiles = {}
    for name, spec in config['profiles'].items():
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', name):
            raise _invalid()
        profiles[name] = _profile(spec, directory)
    for selected in config['roles'].values():
        if selected is not None and (not isinstance(selected, str) or selected not in profiles):
            raise _invalid()
    return profiles


def validate_template(config):
    """Validate a distributable empty-key config without reading credential files.

    Production configuration and template syntax share exactly one validator;
    templates additionally forbid every credential source except literal "".
    """
    try:
        if (not isinstance(config, dict) or not isinstance(config.get('profiles'), dict)
                or any(not isinstance(profile, dict) or profile.get('api_key') != ''
                       or 'api_key_file' in profile for profile in config['profiles'].values())):
            raise _invalid()
        _configured_profiles(config, BACKEND_ROOT)
    except (OSError, UnicodeError, ValueError, TypeError, RecursionError, OverflowError):
        raise _invalid() from None


def select_profile(role, legacy_key_reader, default_model, default_effort):
    """Resolve a role once; absent new config preserves the legacy key reader.

    An explicit missing or malformed file fails closed. An absent role disables
    that role, except upload inherits guidance when upload is omitted. Use null
    to explicitly disable a role (including upload). Empty selected keys disable
    AI without invoking any other profile or provider.
    """
    try:
        if role not in {'guidance', 'upload', 'review'}:
            raise _invalid()
        if os.environ.get('ALLOY_AI_DISABLED') == '1':
            return None
        explicit = os.environ.get('ALLOY_AI_CONFIG_FILE')
        path = _path(explicit, BACKEND_ROOT) if explicit is not None else BACKEND_ROOT / 'ai.local.json'
        if explicit is None and not path.exists():
            # Production read_key owns OPENAI_DISABLED. Preserve dependency
            # injection for the legacy caller and its deterministic test harness.
            key = legacy_key_reader()
            if not key:
                return None
            spec = {'provider': 'openai', 'model': default_model, 'api_key': key}
            if default_effort is not None:
                spec['reasoning_effort'] = default_effort
            return _profile(spec, BACKEND_ROOT)
        if os.environ.get('OPENAI_DISABLED') == '1':
            return None
        config = json.loads(_private_text(path, MAX_CONFIG_BYTES), object_pairs_hook=_unique,
                            parse_constant=lambda _: (_ for _ in ()).throw(_invalid()))
        profiles = _configured_profiles(config, path.parent)
        selected = config['roles'].get(role)
        if role == 'upload' and role not in config['roles']:
            selected = config['roles'].get('guidance')
        result = profiles.get(selected)
        return result if result and result.key else None
    except (OSError, UnicodeError, ValueError, TypeError, RecursionError, OverflowError):
        raise _invalid() from None


def _claude_schema(value):
    """Claude's schema subset; full domain constraints still run locally."""
    unsupported = {'minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum',
                   'multipleOf', 'minLength', 'maxLength', 'pattern', 'format',
                   'maxItems', 'uniqueItems'}
    if isinstance(value, dict):
        result = {}
        for name, child in value.items():
            if name in unsupported or (name == 'minItems' and child not in (0, 1)):
                continue
            # Property names may coincide with JSON Schema keywords.
            if name in ('properties', '$defs', 'definitions') and isinstance(child, dict):
                result[name] = {key: _claude_schema(item) for key, item in child.items()}
            else:
                result[name] = _claude_schema(child)
        return result
    if isinstance(value, list):
        return [_claude_schema(item) for item in value]
    return value


def build_request(profile, payload):
    """Translate the application's Responses-shaped, schema-bound prompt."""
    try:
        instructions = payload['instructions']
        content = payload['input']
        schema = payload['text']['format']['schema']
        budget = payload['max_output_tokens']
        if (not isinstance(instructions, str) or not isinstance(content, str)
                or not isinstance(schema, dict) or type(budget) is not int
                or not 1 <= budget <= 131072):
            raise _invalid()
        protocol = profile.protocol
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        if protocol == 'openai':
            body = dict(payload, model=profile.model, store=False)
            body.pop('reasoning', None)
            if profile.effort is not None:
                body['reasoning'] = {'effort': profile.effort}
            headers['Authorization'] = 'Bearer ' + profile.key
        elif protocol == 'anthropic':
            body = {'model': profile.model, 'system': instructions,
                    'messages': [{'role': 'user', 'content': content}],
                    'max_tokens': min(budget, 128000),
                    'output_config': {'format': {'type': 'json_schema',
                                                  'schema': _claude_schema(schema)}}}
            if profile.effort is not None:
                body['output_config']['effort'] = profile.effort
                body['thinking'] = {'type': 'adaptive'}
            headers.update({'x-api-key': profile.key, 'anthropic-version': '2023-06-01'})
        elif protocol == 'gemini':
            generation = {'maxOutputTokens': min(budget, 65536),
                          'responseFormat': {'text': {'mimeType': 'application/json', 'schema': schema}}}
            if profile.effort is not None:
                generation['thinkingConfig'] = {'thinkingLevel': profile.effort.upper()}
            body = {'systemInstruction': {'parts': [{'text': instructions}]},
                    'contents': [{'role': 'user', 'parts': [{'text': content}]}],
                    'generationConfig': generation}
            headers['x-goog-api-key'] = profile.key
        elif protocol in ('deepseek', 'openai-compatible'):
            # JSON-object APIs need the complete schema in the prompt. The same
            # strict local validator remains authoritative for every provider.
            body = {'model': profile.model,
                    'messages': [{'role': 'system', 'content': instructions +
                                  '\nReturn JSON matching this schema: ' +
                                  json.dumps(schema, ensure_ascii=False, separators=(',', ':'))},
                                 {'role': 'user', 'content': content}],
                    'max_tokens': min(budget, 131072),
                    'response_format': {'type': 'json_object'}, 'stream': False}
            if profile.effort is not None:
                body['reasoning_effort'] = profile.effort
            headers['Authorization'] = 'Bearer ' + profile.key
        else:
            raise _invalid()
        return Request(profile.endpoint, data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                       headers=headers, method='POST')
    except (KeyError, TypeError, ValueError, UnicodeError, RecursionError, OverflowError):
        raise _invalid() from None


def _single_text(texts):
    if (len(texts) != 1 or not isinstance(texts[0], str) or not texts[0].strip()
            or len(texts[0].encode('utf-8')) > MAX_TEXT_BYTES):
        raise _invalid()
    return {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant',
            'content': [{'type': 'output_text', 'text': texts[0]}]}]}


def normalize_response(profile, data):
    """Reject truncation, refusals, tools and ambiguous/multiple completions."""
    try:
        if not isinstance(data, dict) or data.get('error'):
            raise _invalid()
        texts = []
        if profile.protocol == 'openai':
            if data.get('status') != 'completed' or not isinstance(data.get('output'), list):
                raise _invalid()
            for item in data['output']:
                if item.get('type') == 'reasoning':
                    continue
                if item.get('type') != 'message' or item.get('role') != 'assistant':
                    raise _invalid()
                if item.get('status', 'completed') != 'completed':
                    raise _invalid()
                for part in item['content']:
                    if part.get('type') != 'output_text':
                        raise _invalid()
                    texts.append(part['text'])
        elif profile.protocol == 'anthropic':
            if (data.get('type') != 'message' or data.get('role') != 'assistant'
                    or data.get('stop_reason') != 'end_turn' or not isinstance(data.get('content'), list)):
                raise _invalid()
            for part in data['content']:
                if part.get('type') in ('thinking', 'redacted_thinking'):
                    continue
                if part.get('type') != 'text':
                    raise _invalid()
                texts.append(part['text'])
        elif profile.protocol == 'gemini':
            if data.get('promptFeedback', {}).get('blockReason'):
                raise _invalid()
            choices = data['candidates']
            if (not isinstance(choices, list) or len(choices) != 1
                    or choices[0].get('finishReason') != 'STOP'):
                raise _invalid()
            content = choices[0]['content']
            if content.get('role') != 'model' or not isinstance(content.get('parts'), list):
                raise _invalid()
            for part in content['parts']:
                if set(part) - {'text', 'thought', 'thoughtSignature'} or not isinstance(part.get('text'), str):
                    raise _invalid()
                if part.get('thought') is True:
                    continue
                if part.get('thought') not in (None, False):
                    raise _invalid()
                texts.append(part['text'])
        elif profile.protocol in ('deepseek', 'openai-compatible'):
            choices = data['choices']
            if (not isinstance(choices, list) or len(choices) != 1
                    or choices[0].get('finish_reason') != 'stop'):
                raise _invalid()
            message = choices[0]['message']
            if (message.get('role') != 'assistant' or message.get('tool_calls')
                    or message.get('function_call') or message.get('refusal')):
                raise _invalid()
            texts.append(message['content'])
        else:
            raise _invalid()
        return _single_text(texts)
    except (AttributeError, KeyError, TypeError, ValueError, UnicodeError, RecursionError, OverflowError):
        raise _invalid() from None
