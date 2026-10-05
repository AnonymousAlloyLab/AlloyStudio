#!/usr/bin/env python3
"""Task Scheduler entry point. Runs the backend in this Python process."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import runpy
import re
import sys
import xml.etree.ElementTree as ET


def check_web_configuration(public_path, template_path):
    """Allow only exact administration edge-address additions to the packaged XML."""
    def load(path):
        with Path(path).open('rb') as stream:
            data = stream.read(131073)
        if len(data) > 131072:
            raise ValueError('Oversized configuration')
        text = data.decode('utf-8-sig')
        if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
            raise ValueError('Document type declarations are not permitted')
        root = ET.fromstring(text)
        rules = root.findall('./system.webServer/rewrite/rules/rule')
        selected = [r for r in rules if r.get('name') == 'Administration network policy']
        if len(selected) != 1 or len(selected[0].findall('conditions')) != 1:
            raise ValueError('Missing or ambiguous administration policy')
        return root, selected[0].find('conditions')

    public, conditions = load(public_path)
    template, original = load(template_path)
    if list(original):
        raise ValueError('Reference template must retain default-deny administration')
    seen = set()
    for condition in list(conditions):
        if (condition.tag != 'add' or set(condition.attrib) != {'input', 'pattern', 'negate'}
                or condition.get('input') != '{REMOTE_ADDR}' or condition.get('negate') != 'true'
                or list(condition) or (condition.text or '').strip()):
            raise ValueError('Only exact administration edge addresses may be configured')
        pattern = condition.get('pattern')
        address = ipaddress.ip_address(pattern[1:-1].replace(r'\.', '.'))
        canonical = str(address)
        if (isinstance(address, ipaddress.IPv6Address)
                and (address.scope_id is not None or address.ipv4_mapped is not None)):
            raise ValueError('Canonical edge address required')
        if pattern != '^' + re.escape(canonical) + '$' or canonical in seen:
            raise ValueError('Canonical distinct edge address required')
        seen.add(canonical)
        conditions.remove(condition)

    def structure(node):
        return (node.tag, sorted(node.attrib.items()), (node.text or '').strip(),
                (node.tail or '').strip(), tuple(structure(child) for child in node))

    if structure(public) != structure(template):
        raise ValueError('Configuration differs outside the administration edge addresses')
    return sorted(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--check-web-config', type=Path)
    parser.add_argument('--template', type=Path)
    parser.add_argument('--check-network-policy', type=Path, metavar='BACKEND_ROOT')
    parser.add_argument('--trusted-proxy', action='append', default=[])
    parser.add_argument('--admin-network', action='append', default=[])
    args = parser.parse_args()
    if args.check_network_policy is not None:
        if any(value is not None for value in (args.config, args.check_web_config, args.template)):
            parser.error('Network checking cannot be combined with startup or web configuration checking.')
        # File invocation avoids PowerShell 5.1's inline-source quoting and
        # pipeline encoding. Inputs are non-secret addresses; no config is read.
        sys.dont_write_bytecode = True
        sys.path.insert(0, str(args.check_network_policy))
        try:
            from traffic_identity import trusted_proxies, AdminNetworkPolicy
            trusted_proxies(args.trusted_proxy)
            AdminNetworkPolicy(args.admin_network)
        except (ImportError, ValueError):
            parser.error('Invalid network policy or missing traffic_identity.py in the backend.')
        print(json.dumps({'status': 'PASS'}))
        return
    if args.trusted_proxy or args.admin_network:
        parser.error('Network arguments require --check-network-policy.')
    if args.check_web_config is not None or args.template is not None:
        if args.config is not None or args.check_web_config is None or args.template is None:
            parser.error('Configuration checking requires only --check-web-config and --template.')
        try:
            addresses = check_web_configuration(args.check_web_config, args.template)
        except (OSError, ValueError, UnicodeError, ET.ParseError):
            parser.error('The public web.config differs from the packaged policy outside approved exact edge addresses.')
        print(json.dumps({'status': 'PASS', 'administrationEdgeAddresses': len(addresses)}))
        return
    if args.config is None:
        parser.error('Backend startup requires --config.')
    config = json.loads(args.config.read_text(encoding='utf-8-sig'))
    engine_mode = config.get('engine_mode', 'persistent')
    control_port = config.get('control_port', 0)
    if (engine_mode not in ('persistent', 'oneshot') or type(control_port) is not int
            or not 0 <= control_port <= 65535 or control_port == 8080):
        parser.error('Invalid engine_mode or control_port in the protected task configuration.')
    # AP01-C04/C11: exact trusted proxies and administration networks; absent means none.
    trusted = config.get('trusted_proxies', [])
    admin_networks = config.get('admin_networks', [])
    if any(type(values) is not list or any(type(value) is not str for value in values)
           for values in (trusted, admin_networks)):
        parser.error('Invalid trusted_proxies or admin_networks in the protected task configuration.')
    backend = Path(config['backend_root'])
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(backend))
    try:
        from traffic_identity import trusted_proxies, AdminNetworkPolicy
        trusted_proxies(trusted)
        AdminNetworkPolicy(admin_networks)
    except (ImportError, ValueError):
        parser.error('Invalid network policy or missing traffic_identity.py in the backend.')
    resource_profile = config.get('resource_profile', 'constrained')
    if type(config.get('engine_timeout')) is not int or not 1 <= config['engine_timeout'] <= 30:
        parser.error('IIS engine_timeout must be an integer from 1 to 30 seconds.')
    try:
        from execution_profile import resolve_profile
        resolve_profile(resource_profile, workers=config.get('workers'),
                        startup_timeout=config.get('startup_timeout'))
    except (ImportError, TypeError, ValueError):
        parser.error('Invalid execution profile or missing execution_profile.py in the backend.')
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
    # The protected log directory already grants LOCAL SERVICE write access;
    # backend code remains read-only. Parser scratch never enters wwwroot/TEMP.
    os.environ['ALLOY_ENGINE_TMP_ROOT'] = str(Path(config['log_directory']) / 'engine-tmp')
    sys.dont_write_bytecode = True
    sys.argv = [str(backend / 'server.py'), '--host', '127.0.0.1', '--port', '8080',
                '--java', config['java_exe'], '--timeout', str(config['engine_timeout']),
                '--resource-profile', resource_profile, '--engine-mode', engine_mode,
                '--control-port', str(control_port)]
    if config.get('workers') is not None:
        sys.argv.extend(['--workers', str(config['workers'])])
    if config.get('startup_timeout') is not None:
        sys.argv.extend(['--startup-timeout', str(config['startup_timeout'])])
    for origin in config['public_origins']:
        sys.argv.extend(['--public-origin', origin])
    for address in trusted:
        sys.argv.extend(['--trusted-proxy', address])
    for network in admin_networks:
        sys.argv.extend(['--admin-network', network])
    os.chdir(backend)
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
