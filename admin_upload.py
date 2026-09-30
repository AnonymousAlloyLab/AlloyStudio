"""Deterministic, private .als preparation; no HTTP or provider authority here."""
from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

from runtime_dependencies import runtime_classpath, run_engine
from scripts.import_exercises import tokens
from scripts.import_correct_pools import body_token_sha256


MAX_SOURCE = 262144
MAX_GROUPS = 8
MAX_VARIANTS = 64
MAX_BODY = 8192
GENERATED_STARTER = '// Write your predicate here.'
NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]{0,127}\Z')
IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z')
VARIANT = re.compile(r'([iI][nN][vV](0|[1-9][0-9]*))[cC](0|[1-9][0-9]*)\Z')
FIELDS = frozenset(('source', 'filename', 'modelId', 'equivalenceScope'))
SPAN_FIELDS = ('name', 'startByte', 'endByte', 'bodyStartByte', 'bodyEndByte')


class UploadError(ValueError):
    """Fixed private-import errors; never include source, local paths or streams."""


def _hash(value):
    return hashlib.sha256(value if isinstance(value, bytes) else value.encode('utf-8')).hexdigest()


def _text(value, maximum, *, empty=False):
    try:
        if (type(value) is not str or '\0' in value or len(value.encode('utf-8')) > maximum
                or not empty and not value.strip()):
            raise UploadError('Invalid or oversized upload field.')
    except UnicodeError:
        raise UploadError('Upload text must be valid UTF-8.') from None
    return value


def _envelope(envelope):
    if (type(envelope) is not dict or not {'source', 'filename', 'modelId'} <= envelope.keys()
            or set(envelope) - FIELDS):
        raise UploadError('Missing or unknown upload fields.')
    source = _text(envelope['source'], MAX_SOURCE)
    filename = _text(envelope['filename'], 256)
    if not filename.lower().endswith('.als') or '/' in filename or '\\' in filename:
        raise UploadError('Choose an Alloy .als filename, without a directory.')
    model_id = _text(envelope['modelId'], 128)
    if not IDENTIFIER.fullmatch(model_id):
        raise UploadError('Invalid model identifier.')
    scope = envelope.get('equivalenceScope', 5)
    if type(scope) is not int or not 1 <= scope <= 8:
        raise UploadError('Equivalence scope must be an integer from 1 to 8.')
    return source, filename, model_id, scope


def _inventory(source):
    """Source slicing only; the actual Alloy inspector independently checks it."""
    raw = source.encode('utf-8')
    try:
        stream = tokens(raw)
    except ValueError:
        raise UploadError('Unsupported source delimiters, comments or strings.') from None
    rows = []
    names = set()
    cursor = 0
    while cursor < len(stream):
        token = stream[cursor]
        if token.depth != 0:
            cursor += 1
            continue
        if token.value == b'let':
            raise UploadError('Module macros are not supported for uploads.')
        if token.value not in (b'pred', b'fun'):
            cursor += 1
            continue
        kind = token.value.decode('ascii')
        start = stream[cursor - 1].start if cursor and stream[cursor - 1].value == b'private' else token.start
        keyword_start = token.start
        index = cursor + 1
        if index >= len(stream):
            raise UploadError('Incomplete source declaration.')
        implicit_receiver = index + 2 < len(stream) and stream[index + 1].value == b'.'
        if implicit_receiver:
            index += 2
        try:
            name = stream[index].value.decode('ascii')
        except UnicodeError:
            raise UploadError('Unsupported declaration name.') from None
        if not NAME.fullmatch(name) or name in names:
            raise UploadError('Duplicate, overloaded or unsupported declaration name.')
        names.add(name)
        index += 1
        square = 0
        parameters = implicit_receiver
        while index < len(stream):
            current = stream[index]
            if current.value == b'{' and current.depth == 0 and square == 0:
                break
            if current.value == b'[':
                square += 1
                if index + 1 < len(stream) and stream[index + 1].value != b']':
                    parameters = True
            elif current.value == b']':
                square -= 1
            if square < 0:
                raise UploadError('Unsupported declaration header.')
            index += 1
        if index >= len(stream):
            raise UploadError('Incomplete source declaration.')
        opening = stream[index]
        closing = index + 1
        while closing < len(stream) and not (stream[closing].value == b'}' and stream[closing].depth == 0):
            closing += 1
        if closing >= len(stream):
            raise UploadError('Incomplete source declaration.')
        rows.append(dict(name=name, kind=kind, parameterCount=int(parameters), startByte=start,
                         keywordStartByte=keyword_start, endByte=stream[closing].end,
                         bodyStartByte=opening.end, bodyEndByte=stream[closing].start))
        cursor = closing + 1
    return rows


def _body_record(declaration, raw):
    result = {key: declaration[key] for key in SPAN_FIELDS}
    result['bodySha256'] = _hash(raw[declaration['bodyStartByte']:declaration['bodyEndByte']])
    return result


def _retained(length, cuts, insertion):
    before, after = [], []
    cursor = 0
    for start, end in sorted(cuts):
        if type(start) is not int or type(end) is not int or start < cursor or not start < end <= length:
            raise UploadError('Invalid source removal spans.')
        if cursor < start:
            if cursor < insertion < start:
                before.append([cursor, insertion])
                after.append([insertion, start])
            else:
                (before if start <= insertion else after).append([cursor, start])
        if start < insertion < end:
            raise UploadError('Invalid learner slot position.')
        cursor = end
    if cursor < length:
        if cursor < insertion < length:
            before.append([cursor, insertion])
            after.append([insertion, length])
        else:
            (before if length <= insertion else after).append([cursor, length])
    return before, after


def _plan(source, filename, model_id, scope):
    raw = source.encode('utf-8')
    declarations = _inventory(source)
    if any(VARIANT.fullmatch(item['name']) and (item['kind'] != 'pred' or item['parameterCount'])
           for item in declarations):
        raise UploadError('Every invariant variant must be a parameterless oracle.')
    parameterless = [item for item in declarations if item['kind'] == 'pred' and item['parameterCount'] == 0]
    # At most 64 oracle declarations plus one bare starter per group can be
    # valid. Bound this before grouping and variant-ID duplicate comparisons.
    if len(parameterless) > MAX_VARIANTS + MAX_GROUPS:
        raise UploadError('An upload may contain at most 64 solution declarations.')
    groups = {}
    spelling = {}
    for item in parameterless:
        match = VARIANT.fullmatch(item['name'])
        if not match:
            continue
        base, invariant_id, variant_id = match.groups()
        if len(invariant_id) > 6 or len(variant_id) > 6:
            raise UploadError('Invariant and variant numbers may have at most six digits.')
        normalized = base.lower()
        if normalized in spelling and spelling[normalized] != base:
            raise UploadError('Ambiguous mixed-case invariant names.')
        spelling[normalized] = base
        group = groups.setdefault(base, dict(predicate=base, kind='group', variants=[], starter=None))
        if any(int(VARIANT.fullmatch(entry['name']).group(3)) == int(variant_id) for entry in group['variants']):
            raise UploadError('Duplicate invariant variant number.')
        group['variants'].append(item)
    oracle_names = {item['name'] for group in groups.values() for item in group['variants']}
    for base, group in groups.items():
        collisions = [item for item in declarations if item['name'].lower() == base.lower()]
        if collisions:
            if len(collisions) != 1 or collisions[0]['name'] != base or collisions[0] not in parameterless:
                raise UploadError('A grouped learner name collides with another declaration.')
            group['starter'] = collisions[0]
        group['variants'].sort(key=lambda item: int(VARIANT.fullmatch(item['name']).group(3)))
    for item in parameterless:
        if item['name'] in oracle_names or item['name'] in groups:
            continue
        groups[item['name']] = dict(predicate=item['name'], kind='standalone', variants=[item], starter=None)
    values = sorted(groups.values(), key=lambda group: min(item['startByte'] for item in group['variants']))
    if not 1 <= len(values) <= MAX_GROUPS:
        raise UploadError('An upload must contain between one and eight exercises.')
    if sum(len(group['variants']) for group in values) > MAX_VARIANTS:
        raise UploadError('An upload may contain at most 64 solution declarations.')
    oracle_cuts = {(item['startByte'], item['endByte']) for group in values for item in group['variants']}
    documents, witnesses = [], []
    for group in values:
        predicate = group['predicate']
        identifier = model_id + '-' + predicate
        if not IDENTIFIER.fullmatch(identifier):
            raise UploadError('Model and predicate names exceed the exercise identifier limit.')
        starter = group['starter']
        insertion = starter['startByte'] if starter else min(item['startByte'] for item in group['variants'])
        cuts = set(oracle_cuts)
        if starter:
            cuts.add((starter['startByte'], starter['endByte']))
        before, after = _retained(len(raw), cuts, insertion)
        bodies, body_indices, variants = [], {}, []
        for item in group['variants']:
            value = raw[item['bodyStartByte']:item['bodyEndByte']].decode('utf-8')
            _text(value, MAX_BODY)
            token_hash = body_token_sha256(value)
            if token_hash not in body_indices:
                body_indices[token_hash] = len(bodies)
                bodies.append(value)
            variant = _body_record(item, raw)
            variant['solutionIndex'] = body_indices[token_hash]
            variants.append(variant)
        initial = raw[starter['bodyStartByte']:starter['bodyEndByte']].decode('utf-8') if starter else GENERATED_STARTER
        _text(initial, MAX_BODY, empty=True)
        document = dict(id=identifier, title=predicate, group=model_id, predicate=predicate,
                        description=f'Complete the {predicate} predicate in {model_id}.',
                        environmentBefore=b''.join(raw[start:end] for start, end in before).decode('utf-8'),
                        environmentAfter=b''.join(raw[start:end] for start, end in after).decode('utf-8'),
                        predicateHeader='pred ' + predicate + ' ', starter=initial,
                        oracleSolutions=bodies, correctSolutions=[], equivalenceScope=scope)
        witnesses.append(dict(exerciseId=identifier, predicate=predicate, kind=group['kind'], variants=variants,
                              starter=_body_record(starter, raw) if starter else None,
                              insertionByte=insertion, beforeSpans=before, afterSpans=after))
        documents.append(document)
    return documents, dict(version=1, originalSource=source, sourceSha256=_hash(raw), filename=filename,
                           modelId=model_id, equivalenceScope=scope, groups=witnesses), declarations


def validate_witness(witness, documents):
    """Recompute all protected slices and bindings without a runtime parser job."""
    try:
        if type(witness) is not dict or type(documents) not in (list, tuple) or type(witness.get('version')) is not int:
            raise UploadError('Invalid upload witness.')
        source, filename, model_id, scope = _envelope(dict(source=witness['originalSource'],
            filename=witness['filename'], modelId=witness['modelId'], equivalenceScope=witness['equivalenceScope']))
        expected_docs, expected_witness, _ = _plan(source, filename, model_id, scope)
        # JSON equality alone treats booleans like integers. Canonical encoding
        # keeps span/index types exact, as well as rejecting extra metadata.
        if json.dumps(witness, sort_keys=True, ensure_ascii=False) != json.dumps(expected_witness, sort_keys=True, ensure_ascii=False):
            raise UploadError('Upload source witness changed.')
        if len(documents) != len(expected_docs):
            raise UploadError('Upload exercise inventory changed.')
        for document, expected in zip(documents, expected_docs):
            if type(document) is not dict or set(document) != set(expected):
                raise UploadError('Upload exercise binding changed.')
            _text(document['title'], 256)
            _text(document['description'], 8192)
            actual = {key: value for key, value in document.items() if key not in ('title', 'description')}
            protected = {key: value for key, value in expected.items() if key not in ('title', 'description')}
            if json.dumps(actual, sort_keys=True, ensure_ascii=False) != json.dumps(protected, sort_keys=True, ensure_ascii=False):
                raise UploadError('Upload protected source changed.')
        return True
    except (KeyError, TypeError, UnicodeError, ValueError, RecursionError) as error:
        if isinstance(error, UploadError):
            raise
        raise UploadError('Invalid upload witness.') from None


def _remaining(deadline):
    value = deadline - time.monotonic()
    if value <= 0:
        raise UploadError('Upload validation timed out; nothing was imported.')
    return value


def _worker(classpath, main, payload, java, deadline, *, root):
    from exercise_store import parse_json
    environment = {key: value for key, value in os.environ.items() if key.upper() not in {
        'CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS'}}
    request = json.dumps(payload)
    if len(request.encode('utf-8')) > (2097152 if main == 'live.UploadInspector' else 1048576):
        raise UploadError('Upload validation exceeds the engine request limit.')
    try:
        run = run_engine([str(java), '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                              '-cp', classpath, main], input=request, capture_output=True, text=True,
                             encoding='utf-8', timeout=_remaining(deadline), env=environment, check=False, root=root)
        result = parse_json(run.stdout)
    except (OSError, subprocess.TimeoutExpired, ValueError, UnicodeError):
        raise UploadError('Alloy upload validation failed or timed out.') from None
    if run.returncode or type(result) is not dict or result.get('status') != 'ok':
        raise UploadError('The uploaded model has invalid or unsupported declarations or dependencies.')
    return result


def _check_inspection(inspection, source, declarations, witnesses):
    if set(inspection) != {'status', 'sourceSha256', 'moduleName', 'declarations', 'factCalls', 'assertionCalls', 'commandCalls'}:
        raise UploadError('Incomplete Alloy source inspection.')
    if inspection['sourceSha256'] != _hash(source) or type(inspection['declarations']) is not list:
        raise UploadError('Alloy inspection does not match the uploaded source.')
    if len(inspection['declarations']) != len(declarations):
        raise UploadError('Unsupported source-backed declaration layout.')
    names = {item['name'] for item in declarations}
    graph = {}
    for actual, expected in zip(inspection['declarations'], declarations):
        if type(actual) is not dict or set(actual) != set(expected) | {'calls'}:
            raise UploadError('Incomplete Alloy declaration inventory.')
        for key, value in expected.items():
            observed = actual[key]
            if key == 'parameterCount':
                if type(observed) is not int or observed < 0 or bool(observed) != bool(value):
                    raise UploadError('Declaration parameters changed during source inspection.')
            elif type(observed) is not type(value) or observed != value:
                raise UploadError('Alloy source spans do not match the upload.')
        calls = actual['calls']
        if type(calls) is not list or any(type(name) is not str or name not in names for name in calls):
            raise UploadError('Invalid Alloy dependency inventory.')
        graph[actual['name']] = set(calls)
    contexts = {}
    for key in ('factCalls', 'assertionCalls', 'commandCalls'):
        values = inspection[key]
        if type(values) is not list or any(type(name) is not str or name not in names for name in values):
            raise UploadError('Invalid Alloy context dependency inventory.')
        contexts[key] = set(values)
    private = {variant['name'] for group in witnesses for variant in group['variants']}
    starters = {group['starter']['name'] for group in witnesses if group['starter']}
    def reachable(name):
        seen, pending = set(), list(graph[name])
        while pending:
            called = pending.pop()
            if called not in seen:
                seen.add(called)
                pending.extend(graph[called])
        return seen
    for group in witnesses:
        target = group['predicate']
        # A standalone source name remains available as its learner slot; C
        # aliases and other standalone oracle declarations are all removed.
        removed = private - {target}
        retained = names - private - {target}
        if any(graph[name] & removed for name in retained):
            raise UploadError('A retained helper or starter depends on a removed oracle.')
        if any(values & removed for values in contexts.values()):
            raise UploadError('A fact, assertion or command depends on a removed oracle.')
        for variant in group['variants']:
            if reachable(variant['name']) & (private | starters):
                raise UploadError('An oracle depends on another oracle or learner starter.')
        if group['starter'] and graph[target] & private:
            raise UploadError('A learner starter depends on a removed oracle.')


def prepare_upload(root, envelope, *, java='java', timeout=60):
    """Prepare trusted backend data only; this function never writes a database."""
    from exercise_store import _engine_identity, normalize_import, validate_import, StoreError
    source, filename, model_id, scope = _envelope(envelope)
    if type(timeout) not in (int, float) or not 0 < timeout <= 60:
        raise UploadError('Invalid upload validation deadline.')
    deadline = time.monotonic() + timeout
    documents, witness, declarations = _plan(source, filename, model_id, scope)
    classpath = runtime_classpath(Path(root))
    try:
        identity = _engine_identity(classpath)
        inspected = _worker(classpath, 'live.UploadInspector', {'source': source}, java, deadline, root=root)
        _check_inspection(inspected, source, declarations, witness['groups'])
        certificates = []
        for document, group in zip(documents, witness['groups']):
            normalize_import(document)
            # Token-identical references share one stored solution. Distinct
            # original byte spellings still receive an independent reparse and
            # comparison in the generated context before this deduplication.
            raw = source.encode('utf-8')
            originals = list(dict.fromkeys(raw[item['bodyStartByte']:item['bodyEndByte']].decode('utf-8')
                                          for item in group['variants']))
            if originals != document['oracleSolutions']:
                payload = dict(predicate=document['predicate'], starter=document['starter'],
                    sourcePrefix=document['environmentBefore'] + document['predicateHeader'] + '{\n',
                    sourceSuffix='\n}' + document['environmentAfter'], oracleBodies=originals, correctBodies=[], scope=scope)
                result = _worker(classpath, 'live.ExerciseValidator', payload, java, deadline, root=root)
                if type(result.get('evaluatedCandidates')) is not int or result['evaluatedCandidates'] != len(originals):
                    raise UploadError('Alloy did not check every source variant.')
            certificates.append(validate_import(root, document, java=java, timeout=_remaining(deadline)))
        if identity != _engine_identity(classpath):
            raise UploadError('The Alloy engine changed while preparing the upload.')
        _remaining(deadline)
        validate_witness(witness, documents)
        return dict(documents=copy.deepcopy(documents), certificates=certificates, witness=witness)
    except (StoreError, OSError, UnicodeError) as error:
        raise UploadError('The upload did not pass bounded Alloy validation; nothing was imported.') from None
