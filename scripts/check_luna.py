#!/usr/bin/env python3
"""Check live Luna access using one actual, redacted exercise repair trace."""
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
        result = app.explainer.explain(feedback)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['status'] == 'ok' else 1
    finally:
        app.server_close()


if __name__ == '__main__': sys.exit(main())
