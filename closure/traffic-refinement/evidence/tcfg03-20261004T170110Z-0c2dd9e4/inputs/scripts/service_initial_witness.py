"""Finite actual-constructor witness; run in a fresh isolated Python process.

No real exercise/credential content or provider/worker call is needed. A supplied
snapshot object is the explicit snapshot input. Native listener/thread allocation
is real; program counters and lock identities/ownership are intentionally erased.
"""
import dataclasses
from collections import OrderedDict, deque
import itertools
import json
from pathlib import Path
import secrets
import subprocess
import sys
import threading
import time
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
import server
import traffic_http
import runtime_dependencies
import luna
from service_initial_bridge import extract


def witness(root=ROOT):
    root=Path(root)
    graph=extract(root)['graph']
    supplied_snapshot=object()
    objects={'Portal#1':None,'ProcessBudget#1':runtime_dependencies.PROCESS_BUDGET}
    bound_clock=None
    values={}
    with patch.object(server,'load_store',return_value=supplied_snapshot) as loader, \
         patch.object(secrets,'token_hex',side_effect=['a'*32,'b'*32]), \
         patch.object(secrets,'token_bytes',return_value=b'c'*32), \
         patch.object(subprocess,'Popen',side_effect=AssertionError('Unexpected process at startup')) as spawn:
        app=server.Portal(('127.0.0.1',8080),root=root,engine_mode='persistent',java='java')
        objects['Portal#1']=app
        bound_clock=app.http_admission.bucket.last
        externals={'root':root,'snapshot':supplied_snapshot,'generation':'a'*32,
                   'serviceIdentity':'b'*32,'csrfSecret':b'c'*32,'clock':bound_clock}
        names={'Handler':server.Handler,'time.monotonic':time.monotonic,
               'time.monotonic_ns':time.monotonic_ns,'urlopen':luna.urlopen,'read_key':luna.read_key}
        def read(path):
            if path=='$oneshots':return runtime_dependencies._ONESHOT_ROOTS[str(root.resolve())]
            if path.startswith('$oneshots/'):
                return read('$oneshots')[path.split('/',1)[1]]
            prefixes=[name for name in objects if path.startswith(name+'.')]
            if not prefixes:raise AssertionError('Unmapped runtime object '+path)
            name=max(prefixes,key=len);owner=objects[name];fieldpath=path[len(name)+1:]
            field,*indices=fieldpath.split('/')
            if field=='$contents':value=owner
            elif field=='$identity':value='erased'
            elif field=='$lock':value=owner._lock
            elif field=='$permits':value=owner._value
            elif field=='$set':value=owner.is_set()
            elif field=='$started':value=owner._started.is_set()
            elif field=='$maxlen':value=owner.maxlen
            elif field=='$socket':value=owner.socket
            elif field=='$address':value=owner.server_address
            elif field=='$handler':value=owner.RequestHandlerClass
            elif name.startswith('threading.Thread#') and field in ('target','args'):
                value=getattr(owner,'_'+field)
            else:value=getattr(owner,field)
            for index in indices:
                if dataclasses.is_dataclass(value):value=getattr(value,index)
                elif isinstance(value,(list,tuple)):value=value[int(index)]
                else:value=value[index]
            return value
        try:
            checked=0
            for path,kind,expected in graph:
                if kind=='class':
                    if path not in objects:raise AssertionError('Unmapped class '+path)
                    value=objects[path]
                    if expected.startswith('threading.'):
                        factory=getattr(threading,expected.split('.')[1])
                        if expected in ('threading.Lock','threading.RLock'):
                            assert isinstance(value,type(factory())),path
                        else:assert isinstance(value,factory),path
                    elif expected=='deque':assert isinstance(value,deque),path
                    else:assert type(value).__name__==expected,path
                else:
                    value=read(path)
                    if kind=='reference':
                        if expected in objects:assert objects[expected] is value,path
                        else:objects[expected]=value
                    elif kind=='external':
                        target=externals[expected]
                        if expected=='snapshot':assert value is target,path
                        else:assert type(value) is type(target) and value==target,path
                    elif kind=='container':
                        actual=('dict' if dataclasses.is_dataclass(value) else
                                'OrderedDict' if isinstance(value,OrderedDict) else
                                'list' if isinstance(value,deque) and path.endswith('.$contents') else type(value).__name__)
                        assert actual==expected,path
                        children=[p for p,k,v in graph if p.startswith(path+'/') and '/' not in p[len(path)+1:]]
                        actual_len=len(dataclasses.fields(value)) if dataclasses.is_dataclass(value) else len(value)
                        assert actual_len==len(children),path
                    elif kind=='name':assert value is names[expected],path
                    elif kind=='callable':assert value.__self__ is app.scheduler and value.__func__ is type(app.scheduler)._worker,path
                    elif kind=='handle':
                        assert expected=='bound-listener' and value.fileno()>=0,path
                        assert value.getsockname()==('127.0.0.1',8080),path
                    elif kind=='counter':assert value.__reduce__()[1]==(expected,),path
                    elif kind=='none':assert value is None,path
                    else:
                        assert type(value).__name__==kind and value==expected,path
                    values[path]=kind
                checked+=1
            loader.assert_called_once_with(root)
            spawn.assert_not_called()
            assert type(bound_clock) is int
            assert len(app.scheduler.threads)==3
            assert all(t.is_alive() for t in app.scheduler.threads)
            result={'status':'PASS','checkedCells':checked,'objects':len(objects),
                    'jvmLaunches':spawn.call_count,'listenerCount':1,'startedSchedulerThreads':3,
                    'explicitInputsChecked':['root','snapshot','generation','serviceIdentity','csrfSecret','clock'],
                    'secretValuesEmitted':False,'classification':'FINITE_ACTUAL_CONSTRUCTOR_WITNESS'}
        finally:app.server_close()
    assert not any(t.is_alive() for t in app.scheduler.threads)
    return result


if __name__=='__main__':
    print(json.dumps(witness(),sort_keys=True))
