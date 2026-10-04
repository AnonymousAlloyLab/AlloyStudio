"""Bounded optional-delivery characterization; no production requests or secrets."""
import importlib.util
from pathlib import Path
from copy import deepcopy
import json
root = Path(__file__).resolve().parents[3]
snapshot = root / 'build/trf-closure/tcfg03-20261004T165357Z-18a0f46e/inputs'
loader = importlib.util.spec_from_file_location('observation_fixtures', snapshot / 'tests/test_traffic_observation.py')
fixtures = importlib.util.module_from_spec(loader)
loader.loader.exec_module(fixtures)
context = dict(exerciseId='synthetic-exercise', revision=2, requestedMetric='canonical', body='some A and some A', poolSize=2)
baseline = fixtures.feedback()
baseline.update({k: context[k] for k in ('exerciseId', 'revision', 'requestedMetric')})
candidate = deepcopy(baseline)
for key in ('exerciseId', 'revision', 'requestedMetric'):
    del candidate[key]
print(json.dumps(fixtures.observation.compare_observations('feedback', baseline, candidate, context), sort_keys=True))
