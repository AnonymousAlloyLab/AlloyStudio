from pathlib import Path
import hashlib,json,sys
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT))
from traffic_profile import TrafficProfile, normalized_profile
profile=TrafficProfile(public_handlers=17,control_handlers=3)
object.__setattr__(profile,'__dataclass_fields__',{})
copied=normalized_profile(profile)
assert copied.public_handlers != profile.public_handlers
print(json.dumps({'claim':'HP-PRESERVATION','status':'CONSTRUCTED_BREACH',
 'sourceSha256':hashlib.sha256((ROOT/'traffic_profile.py').read_bytes()).hexdigest(),
 'before':[profile.public_handlers,profile.control_handlers],
 'copied':[copied.public_handlers,copied.control_handlers],
 'exactProfileType':type(profile) is TrafficProfile},sort_keys=True,indent=2))
