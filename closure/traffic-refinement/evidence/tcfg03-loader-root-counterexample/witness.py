import sys,types,json
from pathlib import Path
root=Path(sys.argv[1]);sys.path[:0]=[str(root),str(root/'scripts')]
work=Path(__file__).resolve().parent
module=types.ModuleType('reconstructed_initial_adapter');sys.modules[module.__name__]=module
exec(compile((work/'reconstructed-vulnerable-adapter.py.txt').read_text(),'reconstructed-vulnerable-adapter.py.txt','exec'),module.__dict__)
mutant=(work/'mutated-server.py.txt').read_text()
before=module.extract(root)['graph']==module.extract(root,{'server.py':mutant})['graph']
import service_initial_bridge as repaired
try:repaired.extract(root,{'server.py':mutant})
except repaired.BridgeRejected:after=True
else:after=False
assert before and after
print(json.dumps({'classification':'constructed reconstructed vulnerable adapter, not an exact historical-source claim','wrongSnapshotRootWasErased':before,'repairedAdapterRejects':after},sort_keys=True))
