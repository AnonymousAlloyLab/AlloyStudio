"""Finite resource settings; concurrency changes never select a different algorithm."""
from dataclasses import dataclass
from traffic_limits import validated_int, validated_seconds


@dataclass(frozen=True)
class ExecutionProfile:
    name: str
    workers: int
    java_processors: int
    startup_timeout: float


def resolve_profile(name='constrained', workers=None, startup_timeout=None):
    if type(name) is not str or name not in ('constrained', 'standard'):
        raise ValueError('Execution profile must be constrained or standard.')
    processors = 1 if name == 'constrained' else 2
    count = processors if workers is None else min(2, validated_int(workers, maximum=32))
    startup = (20 if name == 'constrained' else 10) if startup_timeout is None else validated_seconds(
        startup_timeout, maximum=30)
    return ExecutionProfile(name, count, processors, startup)
