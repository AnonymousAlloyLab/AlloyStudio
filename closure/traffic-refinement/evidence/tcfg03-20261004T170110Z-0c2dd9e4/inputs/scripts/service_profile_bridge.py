"""Frozen TRF-00 specification/profile/initial-state correspondence checks."""
import ast
import hashlib
import json
from pathlib import Path
from service_initial_bridge import extract,BridgeRejected
from service_profile_lean import generate


def sha(value):
    return hashlib.sha256(value).hexdigest()


def check(root):
    root=Path(root)
    profile=json.loads((root/'closure/traffic-refinement/service-profile.json').read_text())
    expected=json.loads((root/'closure/traffic-refinement/initial-graph.json').read_text())['graph']
    actual=extract(root)
    if actual['graph']!=expected:raise BridgeRejected('Initial graph correspondence differs')
    paths=[r[0] for r in actual['graph']]
    if len(paths)!=len(set(paths)):raise BridgeRejected('Ambiguous initial graph path')
    classes={p for p,k,v in actual['graph'] if k=='class'}
    if any(v not in classes for p,k,v in actual['graph'] if k=='reference'):
        raise BridgeRejected('Unmapped initial object reference')
    # All constructor fields/statements are interpreted, not name-only AST identities.
    for name,source in generate(root).items():
        if (root/'formal/service_profile/ServiceProfile'/name).read_text()!=source:
            raise BridgeRejected('Stale service proof extraction: '+name)
    import http_profile_bridge
    http=http_profile_bridge.check(root)
    if http['status']!='PASS':raise BridgeRejected('Reused HTTP bridge failed')
    import service_numeric_bindings
    numeric=service_numeric_bindings.check(root,profile)
    if numeric['status']!='PASS':raise BridgeRejected('Numeric profile source binding failed')
    import traffic_observation
    observation=traffic_observation.check_baseline(root)
    # Source anchors are identity checks in addition to, not instead of, translation.
    for anchor in profile['sourceAnchors']:
        tree=ast.parse((root/anchor['source']).read_text())
        current=[tree]
        for part in anchor['qualifiedName'].split('.'):
            current=[n for parent in current for n in parent.body
                     if isinstance(n,(ast.ClassDef,ast.FunctionDef,ast.AsyncFunctionDef)) and n.name==part]
        if len(current)!=1:raise BridgeRejected('Ambiguous profile source anchor')
        encoded=ast.dump(current[0],include_attributes=False).encode()
        if sha(encoded)!=anchor['astSha256']:raise BridgeRejected('Changed profile source anchor '+anchor['qualifiedName'])
    return {'status':'PASS','objects':len(classes),'cells':len(paths),
            'constructorStatements':actual['statementCount'],'graphSha256':sha(json.dumps(actual['graph'],sort_keys=True,separators=(',',':')).encode()),
            'numeric':numeric,'observation':observation,'http':http,
            'classification':'CHECKED_RESTRICTED_INITIALIZATION_INTERPRETATION_UNDER_DECLARED_TCB'}
