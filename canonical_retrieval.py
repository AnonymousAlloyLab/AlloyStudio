"""Bounded structural retrieval for ASRL guidance on one learner snapshot.

No solver, embeddings, network, oracle expressions or mutable global cache. The
wire structure is an occurrence graph: node IDs may be shared, addresses may not.
CRUD commands describe the original snapshot, never hypothetical repaired states.
"""
import copy
import hashlib
import json
import re

import repair_grammar as grammar
import repair_language as dsl

VERSION = 1
REPRESENTATION = 'learner-canonical-occurrences'
CANONICAL_METRIC = 'acgn-fast-rewrite-canonical-distance'
AST_METRIC = 'acgn-raw-ast-zhang-shasha-distance'
MAX_NODES = 2048
MAX_PHASES = 128
MAX_BINDINGS = 4096
MAX_STRUCTURE_BYTES = 262144
MAX_CONTEXT_BYTES = 32768
MAX_STEPS = 128
_REASONS = {'node_limit', 'depth_limit', 'byte_limit', 'time_limit', 'unavailable'}
_NODE_ID = re.compile(r'n[0-9]{1,8}\Z')


class RetrievalError(ValueError):
    pass


def _require(value, message='invalid evidence'):
    if not value:
        raise RetrievalError(message)


def _dump(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def _size(value):
    return len(_dump(value).encode('utf-8'))


def _integer(value, maximum):
    _require(type(value) is int and 0 <= value <= maximum)
    return value


def _address(value):
    _require(isinstance(value, list) and len(value) == 3)
    phase, binding, inside = value
    _require(isinstance(inside, list) and len(inside) <= dsl.MAX_DEPTH)
    _require(all(type(i) is int and 0 <= i <= dsl.MAX_INDEX for i in inside))
    if phase == 'T':
        _require(binding == '_')
        return dsl.Address(None, None, tuple(inside))
    _integer(phase, dsl.MAX_INDEX)
    if binding == 'M':
        return dsl.Address(phase, None, tuple(inside))
    _integer(binding, dsl.MAX_INDEX)
    _require(not inside)
    return dsl.Address(phase, binding)


def _wire(address):
    return ['T' if address.temporal is None else address.temporal,
            '_' if address.temporal is None else 'M' if address.quantifier is None else address.quantifier,
            list(address.inside)]


def _parent(address):
    return (dsl.Address(address.temporal, address.quantifier, address.inside[:-1])
            if address.inside else None)


def _unavailable(reason='unavailable'):
    return {'version': VERSION, 'status': 'unavailable', 'representation': REPRESENTATION, 'reason': reason}


def _sanitize(raw):
    _require(isinstance(raw, dict) and type(raw.get('version')) is int and raw['version'] == VERSION)
    _require(raw.get('representation') == REPRESENTATION)
    status = raw.get('status')
    _require(status in ('ok', 'partial', 'unavailable'))
    if status == 'unavailable':
        return _unavailable()
    phases, nodes = raw.get('phases'), raw.get('nodes')
    _require(isinstance(phases, list) and len(phases) <= MAX_PHASES)
    _require(isinstance(nodes, list) and len(nodes) <= MAX_NODES)
    clean_phases = []
    for index, phase in enumerate(phases):
        _require(isinstance(phase, dict) and type(phase.get('index')) is int and phase['index'] == index)
        clean_phases.append({'index': index, 'bindingCount': _integer(phase.get('bindingCount'), MAX_BINDINGS)})
    clean_nodes, index, identities = [], {}, {}
    for node in nodes:
        _require(isinstance(node, dict))
        address = _address(node.get('address'))
        node_id, category, operator = node.get('nodeId'), node.get('category'), node.get('operator')
        _require(isinstance(node_id, str) and _NODE_ID.fullmatch(node_id))
        _require(isinstance(category, str) and category in dsl.CATEGORIES.values())
        _require(operator is None or isinstance(operator, str) and operator in dsl.OPERATORS)
        count = _integer(node.get('childCount'), dsl.MAX_INDEX)
        _require(address not in index)
        if address.temporal is None:
            _require(category == 'TEMPORAL')
        else:
            _require(address.temporal < len(clean_phases))
            if address.quantifier is not None:
                _require(address.quantifier < clean_phases[address.temporal]['bindingCount'])
                _require(category == 'BINDING' and count == 0)
        signature = (category, operator, count, address.component)
        _require(node_id not in identities or identities[node_id] == signature)
        identities[node_id] = signature
        clean = {'address': _wire(address), 'nodeId': node_id, 'category': category, 'childCount': count}
        if operator is not None:
            clean['operator'] = operator
        index[address] = clean
        clean_nodes.append(clean)
    edge_identities = {}
    for address, node in index.items():
        parent = _parent(address)
        if parent is not None:
            _require(parent in index and address.inside[-1] < index[parent]['childCount'])
            edge_key = (index[parent]['nodeId'], address.inside[-1])
            _require(edge_key not in edge_identities or edge_identities[edge_key] == node['nodeId'])
            edge_identities[edge_key] = node['nodeId']
    if status == 'ok':
        _require(dsl.Address(None, None) in index)
        for phase in clean_phases:
            p = phase['index']
            _require(dsl.Address(p, None) in index)
            _require(phase['bindingCount'] <= len(nodes))
            _require(all(dsl.Address(p, q) in index for q in range(phase['bindingCount'])))
        for address, node in index.items():
            _require(node['childCount'] <= len(nodes))
            _require(all(dsl.Address(address.temporal, address.quantifier, address.inside+(i,)) in index
                         for i in range(node['childCount'])))
    clean = {'version': VERSION, 'status': status, 'representation': REPRESENTATION,
             'nodes': clean_nodes, 'phases': clean_phases}
    if status == 'partial':
        reason = raw.get('reason', 'unavailable')
        _require(isinstance(reason, str) and reason in _REASONS)
        clean['reason'] = reason
    _require(_size(clean) <= MAX_STRUCTURE_BYTES)
    return clean


def sanitize_structure(raw):
    """Strict bounded projection; unknown fields (including target text) vanish."""
    try:
        return _sanitize(raw)
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
        return _unavailable()


# Name retained for callers that describe HTTP projection rather than sanitation.
project_structure = sanitize_structure


def _text(value, maximum):
    _require(isinstance(value, str) and len(value.encode('utf-8')) <= maximum)
    return value


def _command(statement):
    # The checked ASRL printer is the only command serializer.
    return dsl.format_program(dsl.Program('0'*64, (statement,))).splitlines()[1].strip()


class RetrievalSnapshot:
    """Prepared, detached retrieval index; .retrieve validates snapshot identity.

    Public accessors return copies. No operation mutates the source graph or
    advances it to a speculative intermediate repair state.
    """
    def __init__(self, feedback, student_body, question='', context_identity=None):
        _require(isinstance(feedback, dict))
        self._metric = feedback.get('metric', CANONICAL_METRIC)
        _require(self._metric in (CANONICAL_METRIC, AST_METRIC))
        structure = sanitize_structure(feedback.get('canonicalStructure'))
        body = _text(student_body, 8192)
        prompt = _text(question, 8192)
        forms = feedback.get('canonicalForm', [])
        if isinstance(forms, str):
            forms = [forms]
        _require(isinstance(forms, list) and len(forms) <= MAX_PHASES)
        forms = [_text(form, 65536) for form in forms]
        _require(sum(len(form.encode('utf-8')) for form in forms) <= 65536)
        _require(context_identity is None or isinstance(context_identity, str))
        if context_identity is not None:
            _text(context_identity, 4096)
        operations = feedback.get('operations')
        _require(isinstance(operations, list) and len(operations) <= MAX_STEPS)
        try:
            rows = grammar.encode_operations(operations, feedback.get('distance'))
        except (ValueError, TypeError):
            raise RetrievalError('invalid trace') from None
        allowed_components = ('a',) if self._metric == AST_METRIC else ('m', 'q', 't')
        _require(all(row['component'] in allowed_components for row in rows))
        # Bind trace as well as learner source/structure, so repeated calls cannot
        # accidentally attach a different selected repair to the same pass ID.
        identity = {'version': VERSION, 'structure': structure, 'metric': self._metric,
                    'studentBody': body, 'canonicalForm': forms, 'question': prompt,
                    'context': context_identity, 'trace': rows}
        self._snapshot_id = hashlib.sha256(_dump(identity).encode('utf-8')).hexdigest()
        self._structure = structure
        self._nodes = {_address(node['address']): node for node in structure.get('nodes', [])}
        self._phases = structure.get('phases', [])
        self._distance = feedback['distance']
        self._steps = [self._project(row) for row in rows]

    @property
    def snapshot_id(self):
        return self._snapshot_id

    def _project(self, row):
        result = {'id': 'operation-'+str(row['operationIndex']+1), 'cost': row['cost'], 'status': 'unavailable'}
        if self._metric == AST_METRIC:
            return dict(result, status='not-applicable', reason='ast_metric')
        if self._structure['status'] == 'unavailable':
            return dict(result, reason='structure_unavailable')
        if row['component'] == 't' and row['action'] == 'a':
            return dict(result, reason='temporal_insertion_unlocated')
        if row['action'] == 'g':
            return dict(result, reason='aggregate_not_atomic')
        try:
            component = {'m': 'matrix', 'q': 'quantifier', 't': 'temporal'}[row['component']]
            address = dsl.address_from_legacy_path(row.get('path'), component)
            verb = {'a': 'CREATE', 'r': 'UPDATE', 'd': 'DELETE'}[row['action']]
            _require(row['role'] == ('i' if verb == 'CREATE' else 'a'), 'role_mismatch')
            names = grammar.dictionary()['operators']
            target = names.get(row.get('replacementOperator'))
            statement = dsl.Statement(verb, address, dsl.CATEGORIES[row['subject']],
                                      ('AT' if component == 'quantifier' else 'NEAR') if verb == 'CREATE' else None,
                                      target)
            if verb == 'CREATE' and component == 'quantifier':
                _require(address.temporal < len(self._phases)
                         and address.quantifier <= self._phases[address.temporal]['bindingCount'], 'missing_position')
                role = 'gap'
            else:
                _require(address in self._nodes, 'missing_position')
                node = self._nodes[address]
                _require(verb == 'CREATE' or node['category'] == statement.category, 'category_mismatch')
                _require('sourceOperator' not in row or node.get('operator') == names[row['sourceOperator']],
                         'operator_mismatch')
                role = 'anchor' if verb == 'CREATE' else 'node'
            return dict(result, status='ok', dsl=_command(statement), address=_wire(address), role=role)
        except (dsl.LanguageError, RetrievalError, KeyError):
            return dict(result, reason='unresolved_position')

    def _descriptor(self, address):
        node = self._nodes.get(address)
        return copy.deepcopy(node) if node is not None else None

    def _neighborhood(self, address, role):
        focus = self._descriptor(address) if role != 'gap' else {'address': _wire(address), 'category': 'BINDING', 'role': 'gap'}
        _require(focus is not None, 'missing_position')
        ancestors, children, siblings, bindings = [], [], [], []
        truncated = False
        cursor = _parent(address)
        while cursor is not None:
            if len(ancestors) < 6:
                if descriptor := self._descriptor(cursor):
                    ancestors.append(descriptor)
            else:
                truncated = True
            cursor = _parent(cursor)
        if role != 'gap':
            count = focus['childCount']
            for child in range(min(count, 6)):
                descriptor = self._descriptor(dsl.Address(address.temporal, address.quantifier, address.inside+(child,)))
                if descriptor is not None:
                    children.append(descriptor)
                else:
                    truncated = True
            truncated |= count > 6
        parent = _parent(address)
        if parent is not None:
            count = self._nodes[parent]['childCount']
            for i in range(max(0, address.inside[-1]-2), min(count, address.inside[-1]+3)):
                if i == address.inside[-1]:
                    continue
                descriptor = self._descriptor(dsl.Address(address.temporal, address.quantifier, address.inside[:-1]+(i,)))
                if descriptor is not None:
                    siblings.append(descriptor)
                else:
                    truncated = True
            truncated |= count-1 > len(siblings)
        if address.temporal is not None:
            count = self._phases[address.temporal]['bindingCount']
            start = max(0, (address.quantifier or 0)-3)
            for q in range(start, min(count, start+6)):
                descriptor = self._descriptor(dsl.Address(address.temporal, q))
                if descriptor is not None:
                    bindings.append(descriptor)
                else:
                    truncated = True
            truncated |= count > len(bindings)
        return {'focus': focus, 'ancestors': ancestors, 'children': children,
                'siblings': siblings, 'bindings': bindings, 'truncated': bool(truncated)}

    def retrieve(self, snapshot_id, *, operation_index=None, address=None, max_bytes=4096):
        """One zero-based trace step or explicit READ address; exact lookup only."""
        _require(type(max_bytes) is int and 2 <= max_bytes <= MAX_CONTEXT_BYTES, 'invalid context budget')
        result = {'snapshotId': self._snapshot_id, 'status': 'unavailable'}
        if snapshot_id != self._snapshot_id:
            return _fit(dict(result, reason='snapshot_mismatch'), max_bytes)
        if self._metric == AST_METRIC:
            return _fit(dict(result, status='not-applicable', reason='ast_metric'), max_bytes)
        if (operation_index is None) == (address is None):
            return _fit(dict(result, reason='select_one_lookup'), max_bytes)
        try:
            if operation_index is not None:
                _require(type(operation_index) is int and 0 <= operation_index < len(self._steps))
                result.update(copy.deepcopy(self._steps[operation_index]))
                if result['status'] != 'ok':
                    return _fit(result, max_bytes)
                resolved = _address(result['address'])
            else:
                resolved = _address(address)
                _require(resolved in self._nodes)
                result.update(status='ok', dsl=_command(dsl.Statement('READ', resolved)),
                              address=_wire(resolved), role='node', cost=0)
            result.update(self._neighborhood(resolved, result['role']))
            if result['truncated']:
                result['status'] = 'partial'
        except (RetrievalError, dsl.LanguageError, KeyError, TypeError):
            return _fit({'snapshotId': self._snapshot_id, 'status': 'unavailable', 'reason': 'invalid_address'}, max_bytes)
        return _fit(result, max_bytes)

    def context(self, max_bytes=MAX_CONTEXT_BYTES):
        _require(type(max_bytes) is int and 2 <= max_bytes <= MAX_CONTEXT_BYTES, 'invalid context budget')
        if self._metric == AST_METRIC:
            return _fit({'version': VERSION, 'status': 'not-applicable', 'reason': 'ast_metric'}, max_bytes)
        positioned = sum(step['cost'] for step in self._steps if step['status'] == 'ok')
        status = ('unavailable' if self._structure['status'] == 'unavailable' else
                  'partial' if positioned != self._distance or self._structure['status'] == 'partial' else 'ok')
        result = {'version': VERSION, 'status': status, 'snapshotId': self._snapshot_id,
                  'representation': REPRESENTATION, 'stageMode': 'immutable-learner-snapshot',
                  'distance': self._distance, 'positionedCost': positioned,
                  'residualCost': self._distance-positioned, 'steps': [], 'omittedSteps': len(self._steps)}
        if not self._steps and self._structure['status'] != 'unavailable':
            result.update(overview=[], omittedOverview=len(self._phases)+1)
        if _size(result) > max_bytes:
            return _fit({'status': 'unavailable', 'reason': 'context_budget'}, max_bytes)
        if 'overview' in result:
            roots = [dsl.Address(phase, None) for phase in range(min(3, len(self._phases)))]
            roots.append(dsl.Address(None, None))
            for root in roots:
                item = self.retrieve(self._snapshot_id, address=_wire(root))
                remaining = max_bytes-_size(result)-1
                if _size(item) > remaining:
                    item = _fit(item, max(2, remaining))
                if 'address' not in item or _size(item) > remaining:
                    result['status'] = 'partial'
                    break
                result['overview'].append(item)
                result['omittedOverview'] -= 1
                if item['status'] != 'ok':
                    result['status'] = 'partial'
            if result['omittedOverview']:
                result['status'] = 'partial'
            while _size(result) > max_bytes and result['overview']:
                result['overview'].pop()
                result['omittedOverview'] += 1
                result['status'] = 'partial'
        for i in range(len(self._steps)):
            item = self.retrieve(self._snapshot_id, operation_index=i)
            remaining = max_bytes-_size(result)-1
            if _size(item) > remaining:
                item = _fit(item, max(2, remaining))
            if item.get('id') != self._steps[i]['id'] or _size(item) > remaining:
                result['status'] = 'partial'
                break
            result['steps'].append(item)
            result['omittedSteps'] -= 1
            if item.get('status') != 'ok':
                result['status'] = 'partial' if status != 'unavailable' else status
        if result['omittedSteps']:
            result['status'] = 'partial' if status != 'unavailable' else status
        # Changes in status/omittedSteps may grow a few bytes at the boundary.
        while _size(result) > max_bytes and result['steps']:
            result['steps'].pop()
            result['omittedSteps'] += 1
            result['status'] = 'partial'
        return result if _size(result) <= max_bytes else _fit(result, max_bytes)


def _fit(value, max_bytes):
    value = copy.deepcopy(value)
    for field in ('ancestors', 'siblings', 'bindings', 'children'):
        if _size(value) <= max_bytes:
            return value
        if field in value:
            value[field] = []
            value['status'] = 'partial'
            value['truncated'] = True
    if _size(value) <= max_bytes:
        return value
    minimal = {key: value[key] for key in ('id', 'cost', 'snapshotId') if key in value}
    minimal.update(status='unavailable', reason='context_budget')
    if _size(minimal) <= max_bytes:
        return minimal
    minimal = {'status': 'unavailable', 'reason': 'context_budget'}
    return minimal if _size(minimal) <= max_bytes else {}


def prepare(feedback, student_body='', *, question='', context_identity=None):
    return RetrievalSnapshot(feedback, student_body, question, context_identity)


def build_context(feedback, student_body='', *, question='', context_identity=None, max_bytes=MAX_CONTEXT_BYTES):
    """Optional prompt enrichment; invalid structure must not disable guidance."""
    _require(type(max_bytes) is int and 2 <= max_bytes <= MAX_CONTEXT_BYTES, 'invalid context budget')
    try:
        return prepare(feedback, student_body, question=question, context_identity=context_identity).context(max_bytes)
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
        return _fit({'version': VERSION, 'status': 'unavailable', 'reason': 'invalid_evidence'}, max_bytes)


def retrieve(feedback, snapshot_id, student_body='', *, operation_index=None, address=None,
             question='', context_identity=None, max_bytes=4096):
    """Stateless repeat-pass entry point; callers may instead reuse prepare()."""
    return prepare(feedback, student_body, question=question, context_identity=context_identity).retrieve(
        snapshot_id, operation_index=operation_index, address=address, max_bytes=max_bytes)
