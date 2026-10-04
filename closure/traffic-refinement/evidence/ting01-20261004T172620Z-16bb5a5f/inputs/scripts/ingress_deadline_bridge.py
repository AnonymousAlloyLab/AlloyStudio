"""Closed successful-path abstraction of the registered read-phase consumers.

Guard placement and its comparison are translated, not inferred from hashes.
Other admitted work is matched against the frozen syntax and interpreted as an
arbitrary time-advancing segment. Python/stdlib and this interpretation are TCB.
"""
import ast
import copy
import hashlib
from pathlib import Path

class BridgeRejected(ValueError): pass

READER=('__init__','begin_headers','begin_body','_recv','readline','read1','read','close')
HANDLER=('setup','handle_one_request','parse_request','read_json_body','public_body','admin_body')
NAMES={'DeadlineReader._recv':'recvProgram','DeadlineReader.readline':'readlineProgram',
       'DeadlineReader.read1':'read1Program','DeadlineReader.begin_body':'beginBodyProgram',
       'Handler.parse_request':'parseProgram','Handler.read_json_body':'bodyProgram'}

def dump(n): return ast.dump(n,include_attributes=False)

def methods(source,cls,names):
    tree=ast.parse(source)
    classes=[n for n in tree.body if isinstance(n,ast.ClassDef) and n.name==cls]
    if len(classes)!=1:raise BridgeRejected('Missing or ambiguous consumer class')
    out={}
    for name in names:
        candidates=[n for n in classes[0].body if isinstance(n,ast.FunctionDef) and n.name==name]
        if len(candidates)!=1:raise BridgeRejected('Missing or ambiguous consumer '+name)
        out[cls+'.'+name]=candidates[0]
    return out

def guard_statement(n,reader):
    call=n.value if isinstance(n,(ast.Expr,ast.Assign)) else None
    if not isinstance(call,ast.Call):return False
    expected=ast.parse(reader+'.check_deadline()',mode='eval').body
    if dump(call)!=dump(expected):return False
    # The body transition must use the returned sample; all other consumers
    # call the same primitive only for its fail-closed check.
    if isinstance(n,ast.Assign):
        return len(n.targets)==1 and dump(n.targets[0])==dump(ast.Name('now',ast.Store()))
    return True

class RemoveGuards(ast.NodeTransformer):
    def __init__(self,reader):self.reader=reader
    def visit_Expr(self,node):return None if guard_statement(node,self.reader) else self.generic_visit(node)
    def visit_Assign(self,node):return None if guard_statement(node,self.reader) else self.generic_visit(node)
    def visit_If(self,node):
        self.generic_visit(node)
        if not node.body:node.body=[ast.Pass()]
        return node

def stripped(node,reader):
    return RemoveGuards(reader).visit(copy.deepcopy(node))

def program(statements,reader):
    result=[];working=False
    for node in statements:
        if guard_statement(node,reader):
            if working:result.append('work');working=False
            result.append('guard')
        elif isinstance(node,ast.Return):
            if working:result.append('work');working=False
            result.append('deliver')
        else:working=True
    if working:result.append('work')
    return result

def extraction(root,traffic_source=None,server_source=None):
    root=Path(root)
    traffic=(root/'traffic_http.py').read_text() if traffic_source is None else traffic_source
    server=(root/'server.py').read_text() if server_source is None else server_source
    current=methods(traffic,'DeadlineReader',READER)
    current.update(methods(server,'Handler',HANDLER))
    template=(root/'formal/ingress_deadlines/consumer-template.py.txt').read_text()
    expected=methods(template,'DeadlineReader',READER);expected.update(methods(template,'Handler',HANDLER))
    for key,node in current.items():
        reader='self' if key.startswith('DeadlineReader.') else 'self.rfile'
        if dump(stripped(node,reader))!=dump(stripped(expected[key],reader)):
            raise BridgeRejected('Unregistered work, field write, call or return: '+key)
    primitive=methods(traffic,'DeadlineReader',('check_deadline',))['DeadlineReader.check_deadline']
    reference=ast.parse('''def check_deadline(self):
    now = self.clock()
    if expired:
        raise TimeoutError('HTTP read deadline exceeded.')
    return now
''').body[0]
    candidate=copy.deepcopy(primitive)
    try:
        condition=candidate.body[1].test
        candidate.body[1].test=ast.Name('expired',ast.Load())
    except (IndexError,AttributeError):raise BridgeRejected('Unknown clock guard structure') from None
    if dump(candidate)!=dump(reference):raise BridgeRejected('Clock sample/return/exception changed')
    if (not isinstance(condition,ast.Compare) or len(condition.ops)!=1
        or dump(condition.left)!=dump(ast.Name('now',ast.Load()))
        or dump(condition.comparators[0])!=dump(ast.parse('self.deadline',mode='eval').body)):
        raise BridgeRejected('Unknown clock comparison operands')
    operators={ast.GtE:'≥',ast.Gt:'>',ast.Lt:'<',ast.LtE:'≤',ast.Eq:'=',ast.NotEq:'≠'}
    if type(condition.ops[0]) not in operators:raise BridgeRejected('Unknown clock comparison')
    programs={}
    for key,name in NAMES.items():
        node=current[key];reader='self' if key.startswith('DeadlineReader.') else 'self.rfile'
        if key=='DeadlineReader.begin_body':
            # The unchanged work template fixes deadline=now+profile.body_seconds
            # and forbids a second clock call. Include only actual guard assignment.
            if any(guard_statement(n,reader) and not isinstance(n,ast.Assign) for n in node.body):
                raise BridgeRejected('Reset must consume the returned checked sample')
            positions=[i for i,n in enumerate(node.body) if guard_statement(n,reader)]
            if positions and positions != [0]:
                raise BridgeRejected('Phase reset must follow exactly one entry guard')
            programs[name]=['guard' for n in node.body if guard_statement(n,reader)]+['reset']
        elif key=='Handler.parse_request':
            # Unchanged branch condition is the stdlib parse success Boolean.
            # Its false branch returns false and cannot dispatch a handler.
            branch=next(n for n in node.body if isinstance(n,ast.If))
            path=[node.body[0],*([n for n in branch.body if not isinstance(n,ast.Pass)]),node.body[-1]]
            programs[name]=program(path,reader)
        elif key=='Handler.read_json_body':
            success=next(n for n in node.body if isinstance(n,ast.Try)).body
            programs[name]=program(success,reader)
        else:programs[name]=program(node.body,reader)
    return {'operator':operators[type(condition.ops[0])],'programs':programs,
            'consumers':{key:hashlib.sha256(dump(value).encode()).hexdigest() for key,value in current.items()},
            'primitive':hashlib.sha256(dump(primitive).encode()).hexdigest()}

def generate(root,traffic_source=None,server_source=None):
    data=extraction(root,traffic_source,server_source)
    output=['import IngressDeadlines.Model','',
        '/- Generated from the actual registered Python comparison and guard placements. -/',
        'namespace AlloyStudio.IngressDeadlines.Extracted',
        'open AlloyStudio.IngressDeadlines.Model','',
        'def checkDeadline (now deadline : Int) : Bool :=',
        '  if now '+data['operator']+' deadline then false else true','']
    for name,actions in data['programs'].items():
        output.append('def '+name+' : List Action := ['+', '.join('.'+a for a in actions)+']')
    output+=['','end AlloyStudio.IngressDeadlines.Extracted','']
    return '\n'.join(output)

def check(root):
    root=Path(root);data=extraction(root);generated=generate(root).encode()
    if generated!=(root/'formal/ingress_deadlines/IngressDeadlines/Extracted.lean').read_bytes():
        raise BridgeRejected('Generated deadline guard/consumer program differs')
    return {'status':'PASS','kind':'CHECKED_RESTRICTED_SUCCESSFUL_PATH_ABSTRACTION_UNDER_DECLARED_TCB',
        'programs':data['programs'],'primitiveSha256':data['primitive'],'consumerAstSha256':data['consumers'],
        'generatedSha256':hashlib.sha256(generated).hexdigest(),
        'trust':'Frozen work/call interpretations, ordinary Python and stdlib dispatch, finite-clock order embedding. Work may advance time arbitrarily; no virtual safety check is inserted at delivery.',
        'excluded':['complete TRF-01','full byte/JSON decoder proof','pre-handler allocation and lane composition','wall-clock time after sampled cuts']}
