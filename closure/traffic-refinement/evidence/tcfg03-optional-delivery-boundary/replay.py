"""Replay archived optional-delivery characterization; not a claimed breach."""
import importlib.util
import json
from pathlib import Path
root = Path(__file__).resolve().parent
loader = importlib.util.spec_from_file_location('archived_observation', root / 'inputs/scripts/traffic_observation.py')
observation = importlib.util.module_from_spec(loader)
loader.loader.exec_module(observation)
witness = json.loads((root / 'missing-delivery-witness.json').read_text())
actual = observation.compare_observations('feedback', witness['baseline'], witness['candidate'], witness['context'])
assert actual == witness['comparison'], (actual, witness['comparison'])
print(json.dumps(actual, sort_keys=True))
