#!/usr/bin/env python3
"""Task Scheduler entry point. Runs the backend in this Python process."""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding='utf-8-sig'))
    backend = Path(config['backend_root'])
    # The manager restricts this entire directory to administrators and the task.
    log = (Path(config['log_directory']) / 'backend.log').open('a', encoding='utf-8', buffering=1)
    sys.stdout = sys.stderr = log
    os.environ['OPENAI_API_KEY_FILE'] = config['key_file']
    os.environ.pop('OPENAI_API_KEY', None)
    os.environ.pop('OPENAI_CONFIG_FILE', None)
    local_config = backend / 'openai.local.json'
    if local_config.exists():
        os.environ['OPENAI_CONFIG_FILE'] = str(local_config)
    os.environ['OPENAI_DISABLED'] = '0' if config['enable_luna'] else '1'
    os.environ['PYTHONIOENCODING'] = 'utf-8'
    os.environ['PYTHONDONTWRITEBYTECODE'] = '1'
    sys.dont_write_bytecode = True
    sys.argv = [str(backend / 'server.py'), '--host', '127.0.0.1', '--port', '8080',
                '--java', config['java_exe'], '--timeout', str(config['engine_timeout']),
                '--workers', str(config['workers'])]
    for origin in config['public_origins']:
        sys.argv.extend(['--public-origin', origin])
    os.chdir(backend)
    sys.path.insert(0, str(backend))
    try:
        runpy.run_path(str(backend / 'server.py'), run_name='__main__')
    except SystemExit as exc:
        if exc.code is None or type(exc.code) is int:
            raise
        print('Backend exited with an unexpected error. Check the protected task configuration.', flush=True)
        raise SystemExit(1) from None
    except BaseException:
        # Do not log exception values that could contain private source or secrets.
        print('Backend stopped unexpectedly. Check installation, runtime paths, and task configuration.', flush=True)
        raise SystemExit(1) from None
    finally:
        log.flush()


if __name__ == '__main__':
    main()
