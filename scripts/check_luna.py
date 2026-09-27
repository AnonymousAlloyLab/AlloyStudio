#!/usr/bin/env python3
"""Check live Luna access using learner code, redacted edits, and public examples."""
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import Portal


def main():
    app = Portal(('127.0.0.1', 0))
    try:
        exercise = app.exercises['graphs-inv1']
        feedback = app.evaluate(exercise, exercise['starter'])
        if feedback.get('status') != 'ok':
            print(json.dumps({'status': 'unavailable', 'message': 'Build the engine before checking Luna.'}))
            return 1
        behavior = app.evaluate_behavior(exercise, exercise['starter'])
        result = app.explainer.explain(feedback, student_body=exercise['starter'],
                                       behavior=behavior if behavior.get('status') == 'ok' else None)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['status'] == 'ok' else 1
    finally:
        app.server_close()


if __name__ == '__main__': sys.exit(main())
