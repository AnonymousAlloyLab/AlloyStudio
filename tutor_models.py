"""Allowlisted learner-chat models backed by private deployment credentials.

This module does no networking. Its public catalogue contains only display
metadata; resolving a selection returns a server-only provider Profile. Model
selection never borrows another role's or another provider's credentials.
"""
from dataclasses import dataclass
from urllib.parse import quote

import ai_provider


@dataclass(frozen=True)
class _Model:
    id: str
    label: str
    provider: str
    efforts: tuple[str, ...]
    default_effort: str


_OPENAI_EFFORTS = ('none', 'low', 'medium', 'high', 'xhigh', 'max')
_MODELS = (
    _Model('gpt-6-luna', 'GPT-6 Luna', 'openai', _OPENAI_EFFORTS, 'low'),
    _Model('gpt-6.1-terra', 'GPT-6.1 Terra', 'openai', _OPENAI_EFFORTS, 'low'),
    _Model('gpt-6.1-sol', 'GPT-6.1 Sol', 'openai', _OPENAI_EFFORTS[1:], 'low'),
    _Model('claude-haiku-5-5', 'Claude Haiku 5.5', 'anthropic', ('low',), 'low'),
    _Model('claude-sonnet-5-5', 'Claude Sonnet 5.5', 'anthropic', ('low',), 'low'),
    _Model('claude-opus-5-5', 'Claude Opus 5.5', 'anthropic', ('high',), 'high'),
    _Model('gemini-3.8-flash', 'Gemini 3.8 Flash', 'gemini', ('minimal',), 'minimal'),
    _Model('gemini-4-argon', 'Gemini 4 Argon', 'gemini', ('low',), 'low'),
)
_BY_ID = {model.id: model for model in _MODELS}
_SUGGESTIONS = {
    'openai': ('gpt-6-luna', 'none'),
    'anthropic': ('claude-haiku-5-5', 'low'),
    'gemini': ('gemini-3.8-flash', 'minimal'),
}


def _invalid():
    return ValueError('The selected chat model or reasoning effort is unavailable.')


def _endpoint(model):
    base = ai_provider.ENDPOINTS[model.provider]
    return base + quote(model.id, safe='') + ':generateContent' if model.provider == 'gemini' else base


def _retarget(source, model, effort):
    if source.protocol != model.provider or not source.key:
        raise _invalid()
    return ai_provider.Profile(model.provider, model.id, source.key, _endpoint(model), effort)


def catalogue(key_reader):
    """List model options enabled by chat credentials, never credentials themselves.

    Configuring a provider authorizes the fixed model stack, but does not assert
    that the provider account can access every model. API failures do not trigger
    substitution. Each call returns detached metadata safe for the browser.
    """
    enabled = ai_provider.chat_profiles(key_reader)
    return [{'id': model.id, 'label': model.label, 'provider': model.provider,
             'efforts': list(model.efforts), 'defaultEffort': model.default_effort}
            for model in _MODELS if model.provider in enabled]


def resolve(model_id, effort, key_reader):
    """Resolve one allowlisted client selection to a private server Profile."""
    if not isinstance(model_id, str) or model_id not in _BY_ID:
        raise _invalid()
    model = _BY_ID[model_id]
    if effort is None:
        effort = model.default_effort
    if not isinstance(effort, str) or effort not in model.efforts:
        raise _invalid()
    source = ai_provider.chat_profiles(key_reader).get(model.provider)
    if source is None:
        raise _invalid()
    return _retarget(source, model, effort)


def suggestions_profile(selected_profile):
    """Use the least-expensive requested model in the selected provider stack.

    This is a separate, bounded suggestion call, never an answer fallback. The
    exact account credential follows the selected profile; no config reread or
    cross-provider selection occurs while a chat pass is in flight.
    """
    if not isinstance(selected_profile, ai_provider.Profile):
        raise _invalid()
    model = _BY_ID.get(selected_profile.model)
    if (model is None or selected_profile.protocol != model.provider
            or selected_profile.endpoint != _endpoint(model)
            or selected_profile.effort not in model.efforts
            or not isinstance(selected_profile.key, str) or not selected_profile.key
            or not selected_profile.key.isascii()
            or len(selected_profile.key) > ai_provider.MAX_KEY_BYTES
            or any(not '!' <= char <= '~' for char in selected_profile.key)):
        raise _invalid()
    suggestion_id, effort = _SUGGESTIONS[model.provider]
    return _retarget(selected_profile, _BY_ID[suggestion_id], effort)
