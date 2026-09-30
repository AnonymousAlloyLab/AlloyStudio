#!/usr/bin/env python3
"""Fetch public pinned FM24 resources without redistributing upstream sources."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

def fetch(args):
    pins = json.loads(Path(__file__).with_name('sources.json').read_text())
    args.destination.mkdir(parents=True, exist_ok=True)
    for repo in pins['repositories']:
        path = args.destination / repo['directory']
        if not path.exists():
            subprocess.run(['git', 'clone', '--no-checkout', repo['url'], str(path)], check=True)
            subprocess.run(['git', '-C', str(path), 'checkout', '--detach', repo['commit']], check=True)
        actual = subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != repo['commit']:
            raise RuntimeError('Existing upstream checkout differs from pin: ' + str(path))
    def download(item):
        path = args.destination / item['path']
        if 'sha256' in item:
            algorithm, expected = 'sha256', item['sha256']
        else:
            algorithm, expected = item['checksum'].split(':', 1)
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_suffix(path.suffix + '.partial')
            urllib.request.urlretrieve(item['url'], temporary)
            if hashlib.new(algorithm, temporary.read_bytes()).hexdigest() != expected:
                temporary.unlink()
                raise RuntimeError('Downloaded checksum mismatch: ' + item['path'])
            temporary.replace(path)
        if hashlib.new(algorithm, path.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Existing checksum mismatch: ' + item['path'])
    dependencies = pins['maven_dependencies']
    if not args.skip_dataset:
        dependencies += pins['dataset']['files']
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(download, dependencies))
    for artifact in pins['artifacts']:
        path = args.destination / artifact['path']
        if hashlib.sha256(path.read_bytes()).hexdigest() != artifact['sha256']:
            raise RuntimeError('Bundled artifact differs: ' + artifact['path'])
    print(json.dumps({'status': 'verified', 'repositories': len(pins['repositories']),
                      'artifacts': len(pins['artifacts']), 'downloads': len(dependencies)}))

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--destination', type=Path, required=True)
    p.add_argument('--skip-dataset', action='store_true')
    fetch(p.parse_args())
