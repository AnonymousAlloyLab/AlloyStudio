"""Constructed counterexample to exact built-in duration classification."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
import traffic_config_bridge as bridge

path = ROOT / 'traffic_limits.py'
spec = importlib.util.spec_from_file_location('frozen_guard_witness', path)
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)

events = []
class Masquerade(type):
    def __eq__(cls, other):
        events.append('metaclass_equality')
        return other is int or other is float

class Unsupported(metaclass=Masquerade):
    def as_integer_ratio(self):
        events.append('unsupported_ratio')
        return 1, 1

value = Unsupported()
exact_builtin = type(value) is int or type(value) is float
accepted = production.validated_seconds(value)
assert not exact_builtin
assert accepted is value
assert events == ['metaclass_equality', 'unsupported_ratio']
result = {
    'affectedClaim': 'TRF00-NUMERIC',
    'blockManifestSha256': hashlib.sha256((ROOT / 'formal/traffic_config/block.json').read_bytes()).hexdigest(),
    'productionSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
    'bridgeStatus': bridge.check(ROOT)['status'],
    'exactBuiltinType': exact_builtin,
    'acceptedUnsupportedObject': accepted is value,
    'events': events,
    'status': 'CONSTRUCTED_BREACH',
}
print(json.dumps(result, indent=2, sort_keys=True))
