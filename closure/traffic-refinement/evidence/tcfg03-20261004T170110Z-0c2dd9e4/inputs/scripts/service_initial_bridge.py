"""Closed AST interpreter for the selected fresh-service initialization profile.

This is a registered semantic adapter, not a Python VM. Every supported call has
an explicit interpretation below; unknown statements/calls fail closed. OS handle
identities are erased, references and initial semaphore/event states are retained.
The proof boundary is successful construction before prewarm or first request.
"""
import ast
from collections import OrderedDict
from dataclasses import dataclass, field
import json
from pathlib import Path


class BridgeRejected(ValueError):
    pass


@dataclass
class Atom:
    kind: str
    value: object


@dataclass
class Object:
    name: str
    cls: str
    fields: dict = field(default_factory=dict)


CLASSES = {
    'Portal': 'server.py', 'BoundedHTTPServer': 'traffic_http.py',
    'PublicViews': 'traffic_http.py', 'Admission': 'traffic_http.py',
    'Scheduler': 'traffic_scheduler.py', 'ResultCache': 'traffic_scheduler.py',
    'EvidenceStore': 'traffic_scheduler.py', 'EnginePool': 'engine_workers.py',
    'ProcessBudget': 'runtime_dependencies.py', 'Explainer': 'luna.py',
    'AuthManager': 'admin_auth.py', 'AdminService': 'admin_service.py',
}


class Interpreter:
    def __init__(self, root, overrides=None):
        self.root = Path(root)
        self.overrides = overrides or {}
        self.modules = {p: ast.parse(self.overrides.get(p, (self.root/p).read_text()))
                        for p in set(CLASSES.values()) | {'traffic_profile.py'}}
        self.classes = {}
        self.objects = []
        self.counts = {}
        self.steps = []
        for name, path in CLASSES.items():
            found = [n for n in self.modules[path].body if isinstance(n, ast.ClassDef) and n.name == name]
            if len(found) != 1:
                raise BridgeRejected('Missing/ambiguous constructor class: '+name)
            self.classes[name] = found[0]
        self.profile = {}
        cls = next(n for n in self.modules['traffic_profile.py'].body
                   if isinstance(n, ast.ClassDef) and n.name == 'TrafficProfile')
        for n in cls.body:
            if isinstance(n, ast.AnnAssign):
                self.profile[n.target.id] = self.expr(n.value, {}, 'TrafficProfile', None)
        self.process = self.construct('ProcessBudget', [], {})

    def atom(self, kind, value):
        return Atom(kind, value)

    def fresh(self, cls):
        self.counts[cls] = self.counts.get(cls, 0) + 1
        obj = Object(cls+'#'+str(self.counts[cls]), cls)
        self.objects.append(obj)
        return obj

    def constructor(self, cls):
        found = [n for n in self.classes[cls].body if isinstance(n, ast.FunctionDef) and n.name == '__init__']
        if len(found) != 1:
            raise BridgeRejected('Missing/ambiguous initializer: '+cls)
        return found[0]

    def construct(self, cls, args, kwargs, target=None):
        obj = self.fresh(cls) if target is None else target
        fn = self.constructor(cls)
        params = [p.arg for p in fn.args.args][1:]
        if fn.args.vararg or fn.args.kwarg or fn.args.posonlyargs:
            raise BridgeRejected('Unregistered constructor arguments')
        env = {'self': obj}
        for p, node in zip(params[-len(fn.args.defaults):], fn.args.defaults):
            env[p] = self.expr(node, env, cls, obj)
        for p, node in zip(fn.args.kwonlyargs, fn.args.kw_defaults):
            if node is not None:
                env[p.arg] = self.expr(node, env, cls, obj)
        env.update(zip(params, args))
        env.update(kwargs)
        if (len(args)>len(params) or set(kwargs) & set(params[:len(args)])
                or set(kwargs)-set(params)-{p.arg for p in fn.args.kwonlyargs}):
            raise BridgeRejected('Constructor argument mismatch')
        self.execute(fn.body, env, cls, obj)
        return obj

    def lookup(self, name, env):
        if name in env:
            return env[name]
        if name == 'PROCESS_BUDGET':
            return self.process
        if name == 'ROOT':
            return Atom('external', 'root')
        if name in ('clock',):
            return Atom('callable', 'time.monotonic')
        return Atom('name', name)

    def expr(self, n, env, cls, obj):
        if isinstance(n, ast.Constant):
            if type(n.value) not in (str, int, float, bool, type(None)):
                raise BridgeRejected('Unsupported constant')
            return n.value
        if isinstance(n, ast.Name):
            return self.lookup(n.id, env)
        if isinstance(n, ast.Attribute):
            value = self.expr(n.value, env, cls, obj)
            if isinstance(value, Object):
                if n.attr in value.fields:
                    return value.fields[n.attr]
                if n.attr == '_worker' and value.cls == 'Scheduler':
                    return Atom('callable', value.name+'._worker')
                raise BridgeRejected('Read before initialized field: '+value.name+'.'+n.attr)
            if isinstance(value, dict) and n.attr in value:
                return value[n.attr]
            if isinstance(value, Atom) and value.kind == 'name':
                return Atom('name', value.value+'.'+n.attr)
            raise BridgeRejected('Unsupported attribute: '+ast.unparse(n))
        if isinstance(n, (ast.Tuple, ast.List, ast.Set)):
            values = [self.expr(e, env, cls, obj) for e in n.elts]
            if isinstance(n, ast.Tuple): return tuple(values)
            if isinstance(n, ast.Set): return set(values)
            return values
        if isinstance(n, ast.Dict):
            if any(k is None for k in n.keys): raise BridgeRejected('Dictionary unpacking')
            return {self.expr(k,env,cls,obj):self.expr(v,env,cls,obj) for k,v in zip(n.keys,n.values)}
        if isinstance(n, ast.Subscript):
            return self.expr(n.value,env,cls,obj)[self.expr(n.slice,env,cls,obj)]
        if isinstance(n, ast.IfExp):
            return self.expr(n.body if self.expr(n.test,env,cls,obj) else n.orelse,env,cls,obj)
        if isinstance(n, ast.BoolOp):
            result = self.expr(n.values[0],env,cls,obj)
            for part in n.values[1:]:
                if isinstance(n.op, ast.Or) and result or isinstance(n.op, ast.And) and not result:
                    return result
                result=self.expr(part,env,cls,obj)
            return result
        if isinstance(n, ast.UnaryOp) and isinstance(n.op,ast.Not):
            return not self.expr(n.operand,env,cls,obj)
        if isinstance(n, ast.BinOp):
            a,b=self.expr(n.left,env,cls,obj),self.expr(n.right,env,cls,obj)
            if type(a) not in (int,float) or type(b) not in (int,float): raise BridgeRejected('Non-numeric arithmetic')
            if isinstance(n.op,ast.Add):return a+b
            if isinstance(n.op,ast.Mult):return a*b
            raise BridgeRejected('Unsupported arithmetic')
        if isinstance(n,ast.Compare):
            left=self.expr(n.left,env,cls,obj)
            for op,part in zip(n.ops,n.comparators):
                right=self.expr(part,env,cls,obj)
                if isinstance(op,(ast.Is,ast.IsNot)):
                    same=left is right
                    if isinstance(left,Atom) and isinstance(right,Atom):
                        if left.kind!='name' or right.kind!='name' or left.value not in {'int','float','bool','dict','str','list','tuple'} or right.value not in {'int','float','bool','dict','str','list','tuple'}:
                            raise BridgeRejected('Unregistered identity operands')
                        same=left.value==right.value
                    elif type(left) in (int,float,str) or type(right) in (int,float,str):
                        raise BridgeRejected('Unregistered scalar object identity')
                    valid=same if isinstance(op,ast.Is) else not same
                elif isinstance(op,ast.Eq):valid=left==right
                elif isinstance(op,ast.NotEq):valid=left!=right
                elif isinstance(op,ast.NotIn):valid=left not in right
                elif isinstance(op,ast.In):valid=left in right
                else:raise BridgeRejected('Unsupported comparison')
                if not valid:return False
                left=right
            return True
        if isinstance(n, ast.DictComp):
            if len(n.generators)!=1 or n.generators[0].ifs: raise BridgeRejected('Unsupported comprehension')
            g=n.generators[0]; result={}
            for value in self.expr(g.iter,env,cls,obj):
                local=dict(env); self.bind(g.target,value,local,obj)
                result[self.expr(n.key,local,cls,obj)]=self.expr(n.value,local,cls,obj)
            return result
        if isinstance(n,ast.GeneratorExp):
            if len(n.generators)!=1 or n.generators[0].ifs: raise BridgeRejected('Unsupported generator')
            g=n.generators[0]; result=[]
            for value in self.expr(g.iter,env,cls,obj):
                local=dict(env);self.bind(g.target,value,local,obj)
                result.append(self.expr(n.elt,local,cls,obj))
            return result
        if isinstance(n,ast.JoinedStr):
            return ''.join(str(self.expr(p.value,env,cls,obj)) if isinstance(p,ast.FormattedValue) else p.value for p in n.values)
        if isinstance(n,ast.Call):
            return self.call(n,env,cls,obj)
        raise BridgeRejected('Unsupported expression: '+ast.unparse(n))

    def call(self,n,env,cls,obj):
        name=ast.unparse(n.func)
        args=[self.expr(v,env,cls,obj) for v in n.args]
        if any(k.arg is None for k in n.keywords):raise BridgeRejected('Keyword unpacking')
        kw={k.arg:self.expr(k.value,env,cls,obj) for k in n.keywords}
        plain_signatures = {'Path':(1,1), 'str':(1,1), 'TrafficProfile':(0,0),
            'normalized_profile':(1,1), 'initial_admission':(3,3), 'TokenBucket.from_initial':(1,1),
            'load_store':(1,1), 'open_engine_admission':(1,1), 'secrets.token_hex':(1,1),
            'secrets.token_bytes':(1,1), 'set':(0,1), 'frozenset':(0,1),
            'min':(1,32), 'max':(1,32), 'sum':(1,1), 'range':(1,3), 'type':(1,1),
            'itertools.count':(1,1), 'Path(root).resolve':(0,0)}
        if name in plain_signatures:
            lower,upper=plain_signatures[name]
            if kw or not lower<=len(args)<=upper:raise BridgeRejected('Unregistered call signature: '+name)
        if name in CLASSES:return self.construct(name,args,kw)
        if name=='super().__init__':
            if cls=='Portal' and (len(args)!=2 or set(kw)!={'traffic_profile'}):raise BridgeRejected('Portal superclass signature')
            if cls=='BoundedHTTPServer' and (len(args)!=2 or kw):raise BridgeRejected('Listener superclass signature')
            if cls=='ResultCache' and (args or kw):raise BridgeRejected('Cache superclass signature')
            if cls=='Portal':return self.construct('BoundedHTTPServer',args,kw,target=obj)
            if cls=='BoundedHTTPServer':
                # Registered CPython HTTPServer successful bind/listen abstraction.
                obj.fields['$socket']=Atom('handle','bound-listener')
                obj.fields['$address']=args[0]
                obj.fields['$handler']=args[1]
                return None
            if cls=='ResultCache':obj.fields['$contents']=OrderedDict();return None
            raise BridgeRejected('Unknown superclass adapter')
        if name in ('validated_int','validated_seconds'):
            # TCFG01 scalar program is checked separately and applied to real values.
            from traffic_limits import validated_int,validated_seconds
            return (validated_int if name=='validated_int' else validated_seconds)(*args,**kw)
        if name=='TrafficProfile':return dict(self.profile)
        if name=='normalized_profile':
            if args[0]!=self.profile:raise BridgeRejected('Nonselected HTTP profile')
            return dict(args[0])
        if name=='initial_admission':
            if args[0]!=self.profile or args[1] is not False:raise BridgeRejected('Nonselected HTTP lane')
            p=args[0]; capacity=p['public_burst']*1000000000
            # TCFG02 checked transition reused; no future admission interpretation.
            state=dict(limit=p['public_handlers'],capacity=capacity,rate=p['public_rate'],
                       credit=capacity,last=Atom('external','clock'),active=0,peak=0,accepted=0,rejected=0,
                       owners=set(),anonymous_owners=[],peers=OrderedDict())
            return (dict(p),state)
        if name=='TokenBucket.from_initial':
            b=self.fresh('TokenBucket');b.fields={k:args[0][k] for k in ('capacity','rate','credit','last')};return b
        if name in ('threading.Lock','threading.RLock','threading.Condition'):
            if kw or len(args)>(1 if name=='threading.Condition' else 0):raise BridgeRejected('Lock arguments')
            h=self.fresh(name);h.fields['$identity']='erased'
            if args:h.fields['$lock']=args[0]
            return h
        if name=='threading.BoundedSemaphore':
            if kw or len(args)!=1 or type(args[0]) is not int or args[0]<=0:raise BridgeRejected('Semaphore arguments')
            h=self.fresh(name);h.fields['$permits']=args[0];return h
        if name=='threading.Event':
            if args or kw:raise BridgeRejected('Event arguments')
            h=self.fresh(name);h.fields['$set']=False;return h
        if name=='threading.Thread':
            if args or set(kw)!={'target','args','name','daemon'}:raise BridgeRejected('Thread arguments')
            h=self.fresh(name);h.fields=dict(kw);h.fields['$started']=False;return h
        if name=='secrets.token_hex':
            if args!=[16] or kw or cls!='Portal':raise BridgeRejected('Unknown random hex input')
            return Atom('external','generation' if 'generation' not in obj.fields else 'serviceIdentity')
        if name=='secrets.token_bytes':
            if args!=[32] or kw or cls!='AuthManager':raise BridgeRejected('Unknown random byte input')
            return Atom('external','csrfSecret')
        if name=='load_store':
            if args!=[Atom('external','root')] or kw:raise BridgeRejected('Snapshot root mismatch')
            return Atom('external','snapshot')
        if name=='Path':
            if len(args)!=1 or kw:raise BridgeRejected('Path arguments')
            return args[0]
        if name=='Path(root).resolve':
            if args or kw or env['root']!=Atom('external','root'):raise BridgeRejected('Resolve root arguments')
            return env['root']
        if name=='str':return args[0] if isinstance(args[0],Atom) else str(args[0])
        if name=='open_engine_admission':
            if args!=[Atom('external','root')] or kw:raise BridgeRejected('Admission root mismatch')
            self.oneshots={'closed':False,'active':0};return None
        if name=='dict':return dict(*args,**kw)
        if name=='OrderedDict':return OrderedDict(*args,**kw)
        if name=='set':return set(*args)
        if name=='frozenset':return frozenset(*args)
        if name=='min':return min(args)
        if name=='max':return max(args)
        if name=='sum':return sum(args[0])
        if name=='range':return range(*args)
        if name=='type':return Atom('name',type(args[0]).__name__)
        if name=='itertools.count':return Atom('counter',args[0])
        if name=='deque':
            if args or set(kw)!={'maxlen'}:raise BridgeRejected('Deque input')
            h=self.fresh('deque');h.fields={'$contents':[],'$maxlen':kw['maxlen']};return h
        if isinstance(n.func,ast.Attribute):
            owner=self.expr(n.func.value,env,cls,obj)
            if n.func.attr=='values' and isinstance(owner,dict):return list(owner.values())
            if n.func.attr=='items' and isinstance(owner,dict):return list(owner.items())
            if n.func.attr=='append' and isinstance(owner,list):owner.append(args[0]);return None
            if isinstance(owner,Object) and owner.cls=='threading.Event' and n.func.attr=='set':
                if args or kw:raise BridgeRejected('Event set arguments')
                owner.fields['$set']=True;return None
            if isinstance(owner,Object) and owner.cls=='threading.Thread' and n.func.attr=='start':
                if args or kw:raise BridgeRejected('Thread start arguments')
                owner.fields['$started']=True;return None
        raise BridgeRejected('Unsupported call: '+name)

    def bind(self,n,value,env,obj):
        if isinstance(n,ast.Name):env[n.id]=value;return
        if isinstance(n,(ast.Tuple,ast.List)):
            if len(n.elts)!=len(value):raise BridgeRejected('Destructuring mismatch')
            for target,part in zip(n.elts,value):self.bind(target,part,env,obj)
            return
        if isinstance(n,ast.Attribute) and isinstance(n.value,ast.Name) and n.value.id=='self':
            obj.fields[n.attr]=value;return
        raise BridgeRejected('Unregistered assignment target')

    def execute(self,body,env,cls,obj):
        for n in body:
            self.steps.append({'class':cls,'statement':ast.unparse(n)})
            if isinstance(n,ast.Assign):
                value=self.expr(n.value,env,cls,obj)
                for target in n.targets:self.bind(target,value,env,obj)
            elif isinstance(n,ast.Expr):self.expr(n.value,env,cls,obj)
            elif isinstance(n,ast.If):self.execute(n.body if self.expr(n.test,env,cls,obj) else n.orelse,env,cls,obj)
            elif isinstance(n,ast.Try):
                if n.finalbody or n.orelse:raise BridgeRejected('Unsupported try boundary')
                self.execute(n.body,env,cls,obj)
            elif isinstance(n,ast.For):
                if n.orelse:raise BridgeRejected('Loop else')
                values=list(self.expr(n.iter,env,cls,obj))
                if len(values)>32:raise BridgeRejected('Unbounded initializer loop')
                for value in values:
                    self.bind(n.target,value,env,obj);self.execute(n.body,env,cls,obj)
            elif isinstance(n,ast.ImportFrom):
                if ast.unparse(n)!='from traffic_scheduler import ResultCache':raise BridgeRejected('Unsupported import')
            elif isinstance(n,ast.Raise):raise BridgeRejected('Selected profile rejected by constructor')
            else:raise BridgeRejected('Unsupported statement: '+ast.unparse(n))

    def project(self):
        rows=[]
        def walk(path,value):
            if isinstance(value,Object):rows.append([path,'reference',value.name]);return
            if isinstance(value,Atom):rows.append([path,value.kind,value.value]);return
            if value is None:rows.append([path,'none',None]);return
            if type(value) in (str,int,bool,float):rows.append([path,type(value).__name__,value]);return
            kind=type(value).__name__
            if isinstance(value,dict):
                rows.append([path,'container',kind]);
                for k,v in value.items():walk(path+'/'+str(k),v)
                return
            if isinstance(value,(list,tuple,set,frozenset)):
                rows.append([path,'container',kind]);
                for i,v in enumerate(value):walk(path+'/'+str(i),v)
                return
            raise BridgeRejected('Unprojectable state value')
        for obj in self.objects:
            rows.append([obj.name,'class',obj.cls])
            for name,value in obj.fields.items():walk(obj.name+'.'+name,value)
        walk('$oneshots',self.oneshots)
        return rows


def extract(root,overrides=None):
    machine=Interpreter(root,overrides)
    machine.construct('Portal',[('127.0.0.1',8080)],dict(root=Atom('external','root'),
                      engine_mode='persistent',java='java'))
    return {'schemaVersion':1,'graph':machine.project(),'steps':machine.steps,
            'objectCount':len(machine.objects),'statementCount':len(machine.steps)}


if __name__=='__main__':
    print(json.dumps(extract(Path(__file__).resolve().parents[1]),sort_keys=True,indent=2))
