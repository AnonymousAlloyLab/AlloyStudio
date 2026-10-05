"""Explicit business capabilities for socket-only ingress test applications.

These fixtures retain the real Handler, admission gates and deadline readers.
Only health/catalogue/static reads and an explicitly supplied channel callback
are available; every other business operation fails and leaves an assertion
trace even if the production handler converts that failure to an HTTP error.
"""
from types import SimpleNamespace


class UnexpectedCapability:
    def __init__(self, name, calls):
        self.name, self.calls = name, calls

    def reject(self, operation):
        self.calls.append(self.name + operation)
        raise AssertionError('Unexpected business capability: ' + self.name + operation)

    def __getattr__(self, name):
        return self.reject('.' + name)

    def __call__(self, *args, **kwargs):
        return self.reject('()')

    def __enter__(self):
        return self.reject('.__enter__()')

    def __exit__(self, *args):
        return self.reject('.__exit__()')

    def __truediv__(self, other):
        return self.reject('/path')


def install_route_fixture(app, exercises, *, root=None, issue_channel=None):
    """Populate every named RouteServices input without loading a real Portal."""
    unexpected = []
    app.root = root if root is not None else UnexpectedCapability('root', unexpected)
    app.exercises = exercises
    app.snapshot = SimpleNamespace(exercises=exercises)
    app.public_origins = frozenset()
    app.scheduler = UnexpectedCapability('scheduler', unexpected)
    if issue_channel is not None:
        app.scheduler.issue_channel = issue_channel
    # Explicit names keep interface drift visible instead of supplying any
    # missing service dynamically. None of these callbacks may be used here.
    for name in ('admin_auth', 'admin', 'evidence', 'explainer', 'cache_lock',
                 'behavior_cache', 'capture', 'diagnostics', 'evaluate',
                 'evaluate_behavior', 'evidence_identity', 'behavior_payload', '_key'):
        setattr(app, name, UnexpectedCapability(name, unexpected))
    app.unexpected_route_calls = unexpected
    return app
