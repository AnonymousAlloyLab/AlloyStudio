"""Pure source-bound dependency projections; no authority, I/O or solver here.

The private upload witness binds the independently inspected declaration graph.
Runtime completion authority is deliberately separate from these fixed slots.
"""
from __future__ import annotations

import hashlib
import json
import re


GENERATED_STARTER = '// Write your predicate here.'
PLAN_FIELDS = frozenset(('version', 'direct', 'dependencies', 'slots', 'blockedPredicates',
                         'validationEnvironmentBefore', 'validationEnvironmentAfter'))
SLOT_FIELDS = frozenset(('side', 'startByte', 'endByte', 'header', 'predicate', 'exerciseId'))
NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]{0,127}\Z')
ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z')


def validate_plan(record):
    """Reject malformed internal metadata before any replacement or projection."""
    plan = record.get('practicePlan')
    if plan is None:
        return None
    if (type(plan) is not dict or set(plan) != PLAN_FIELDS
            or type(plan['version']) is not int or plan['version'] != 1):
        raise ValueError('Invalid practice dependency plan.')
    for key in ('direct', 'dependencies', 'blockedPredicates'):
        values = plan[key]
        expression = NAME if key == 'blockedPredicates' else ID
        maximum = 356 if key == 'blockedPredicates' else 99
        if (type(values) is not list or len(values) > maximum
                or any(type(value) is not str or not expression.fullmatch(value) for value in values)
                or len(set(values)) != len(values)):
            raise ValueError('Invalid practice dependency inventory.')
    if not set(plan['direct']) <= set(plan['dependencies']) or record['id'] in plan['dependencies']:
        raise ValueError('Invalid practice dependency closure.')
    if type(plan['slots']) is not list or len(plan['slots']) > 99:
        raise ValueError('Invalid practice slot inventory.')
    seen, ranges = set(), {'before': [], 'after': []}
    environments = {'before': record['environmentBefore'].encode('utf-8'),
                    'after': record['environmentAfter'].encode('utf-8')}
    for slot in plan['slots']:
        if (type(slot) is not dict or set(slot) != SLOT_FIELDS or slot['side'] not in ranges
                or type(slot['startByte']) is not int or type(slot['endByte']) is not int
                or type(slot['exerciseId']) is not str or not ID.fullmatch(slot['exerciseId'])
                or slot['exerciseId'] in seen or slot['exerciseId'] == record['id']
                or type(slot['predicate']) is not str or not NAME.fullmatch(slot['predicate'])
                or type(slot['header']) is not str or len(slot['header'].encode('utf-8')) > 8192):
            raise ValueError('Invalid practice slot.')
        value = environments[slot['side']]
        start, end = slot['startByte'], slot['endByte']
        if (not 0 <= start < end <= len(value)
                or value[start:end] != GENERATED_STARTER.encode('utf-8')):
            raise ValueError('Practice slot source changed.')
        # Decode endpoints independently: neither boundary may bisect UTF-8.
        value[:start].decode('utf-8'); value[:end].decode('utf-8')
        ranges[slot['side']].append((start, end))
        seen.add(slot['exerciseId'])
    if not set(plan['dependencies']) <= seen:
        raise ValueError('A prerequisite slot is missing.')
    for spans in ranges.values():
        spans.sort()
        if any(previous[1] > current[0] for previous, current in zip(spans, spans[1:])):
            raise ValueError('Practice slots overlap.')
    for key in ('validationEnvironmentBefore', 'validationEnvironmentAfter'):
        if (type(plan[key]) is not str or '\0' in plan[key]
                or len(plan[key].encode('utf-8')) > 262144):
            raise ValueError('Invalid private dependency environment.')
    return plan


def render_context(record, bodies_map):
    """Substitute only fixed prerequisite bodies into the witnessed TODO slots."""
    plan = validate_plan(record)
    expected = set(plan['dependencies']) if plan else set()
    if type(bodies_map) is not dict or set(bodies_map) != expected:
        raise ValueError('The verified prerequisite context is incomplete.')
    if not plan:
        return dict(record)
    from exercise_store import body
    for value in bodies_map.values():
        body(value)
    result = dict(record)
    result.pop('practicePlan', None)
    for side, field in (('before', 'environmentBefore'), ('after', 'environmentAfter')):
        value = record[field].encode('utf-8')
        slots = sorted((slot for slot in plan['slots'] if slot['side'] == side
                        and slot['exerciseId'] in bodies_map), key=lambda slot: slot['startByte'], reverse=True)
        for slot in slots:
            value = value[:slot['startByte']] + bodies_map[slot['exerciseId']].encode('utf-8') + value[slot['endByte']:]
        result[field] = value.decode('utf-8')
    if len((result['environmentBefore'] + result['predicateHeader'] + '{\n'
            + result.get('starter', '') + '\n}' + result['environmentAfter']).encode('utf-8')) > 262144:
        raise ValueError('The resolved prerequisite model exceeds its byte limit.')
    return result


def validation_record(record):
    """Return the immutable author-oracle context used only by private validation."""
    plan = validate_plan(record)
    if not plan:
        return dict(record)
    result = dict(record, environmentBefore=plan['validationEnvironmentBefore'],
                  environmentAfter=plan['validationEnvironmentAfter'])
    result.pop('practicePlan', None)
    return result


def public_slots(record):
    """Expose learner body character offsets, never private prerequisite bodies."""
    plan = validate_plan(record)
    if not plan:
        return []
    result = []
    environments = {'before': record['environmentBefore'].encode('utf-8'),
                    'after': record['environmentAfter'].encode('utf-8')}
    for slot in plan['slots']:
        raw = environments[slot['side']]
        result.append(dict(id=slot['exerciseId'], part='environmentBefore' if slot['side'] == 'before' else 'environmentAfter',
                           start=len(raw[:slot['startByte']].decode('utf-8').encode('utf-16-le')) // 2,
                           end=len(raw[:slot['endByte']].decode('utf-8').encode('utf-16-le')) // 2))
    return result


def public_plan(record):
    plan = validate_plan(record)
    return (dict(version=1, direct=list(plan['direct']), dependencies=list(plan['dependencies']),
                 slots=public_slots(record), blockedPredicates=list(plan['blockedPredicates']))
            if plan else None)


def dependency_plan_sha(record):
    plan = validate_plan(record)
    if not plan:
        raise ValueError('Missing practice dependency plan.')
    return hashlib.sha256(json.dumps(plan, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode('utf-8')).hexdigest()
