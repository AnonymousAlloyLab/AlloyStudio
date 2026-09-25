#!/usr/bin/env python3
"""Freeze and execute finite offline closure; JSON evidence is authoritative."""
from __future__ import annotations

import argparse
import ast
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "1.0"
SKIP_DIRS = {".git", "__pycache__", ".pytest_cache", "test-results", "playwright-report", "secrets"}
SKIP_PATHS = {"closure/runs", "build", "engine/build", "node_modules/.cache"}
BLOCKING_CODES = {
    "INPUT_MUTATION", "CLAIM_MUTATION", "UNDECLARED_DEPENDENCY", "VERIFIER_FAILURE",
    "VERIFIER_NOT_RUN", "STALE_OR_UNBOUND_EVIDENCE", "UNMAPPED_IMPLEMENTATION_OBJECT",
    "AMBIGUOUS_CORRESPONDENCE", "NON_MECHANICAL_CORRESPONDENCE", "MISSING_WITNESS",
    "WITNESS_INVALID", "CLEAN_BUILD_FAILURE", "NONDETERMINISM", "ORPHAN_CLAIM",
    "UNDEFINED_BEHAVIOR_DEPENDENCY", "SCOPE_LEAK",
}


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(canonical(value))


def inventory(root, exclude=True):
    """Hash files, symlink targets and executable permission bits, in path order."""
    root = Path(root)
    entries = []
    for directory, folders, names in os.walk(root, followlinks=False):
        base = Path(directory)
        folders[:] = sorted(name for name in folders if not (exclude and (
            name in SKIP_DIRS or str((base / name).relative_to(root)) in SKIP_PATHS)))
        for name in sorted(names + [n for n in folders if (base / n).is_symlink()]):
            path = base / name
            relative = str(path.relative_to(root))
            if exclude and (name in {".env", "openai.key", "openai.local.json"} or
                            name.startswith(".env.") and name != ".env.example" or
                            name.endswith(".pyc")):
                continue
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                target = os.readlink(path)
                if exclude and (not path.resolve().is_relative_to(root.resolve()) or not path.exists()):
                    raise ValueError(f"Unfrozen external or broken input symlink: {relative}")
                entries.append({"path": relative, "kind": "symlink", "target": target,
                                "sha256": sha(target.encode())})
            elif stat.S_ISREG(mode):
                entries.append({"path": relative, "kind": "file", "sha256": file_sha(path),
                                "executable": bool(mode & 0o111)})
            else:
                raise ValueError(f"Unsupported verification input: {relative}")
    return sorted(entries, key=lambda entry: entry["path"])


def copy_inputs(source, target, entries):
    target.mkdir(parents=True)
    for entry in entries:
        destination = target / entry["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if entry["kind"] == "symlink":
            destination.symlink_to(entry["target"])
        else:
            shutil.copy2(source / entry["path"], destination)


def version(command):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=20, check=True)
    return (result.stdout + result.stderr).strip()


def tcb_record():
    commands = {"python": [sys.executable, "--version"], "java": ["java", "-version"],
                "javac": ["javac", "-version"], "node": ["node", "--version"],
                "bash": ["bash", "--version"], "unshare": ["unshare", "--version"],
                "ip": ["ip", "-Version"]}
    versions = {name: version(command) for name, command in commands.items()}
    executables = {name: {"path": str(Path(shutil.which(command[0])).resolve()),
                          "sha256": file_sha(Path(shutil.which(command[0])).resolve())}
                   for name, command in commands.items()}
    browser = Path(version(["node", "-e", "process.stdout.write(require('playwright').chromium.executablePath())"]))
    browser_cache = browser.parents[2]
    if not browser.is_file():
        raise FileNotFoundError("The locked Playwright Chromium executable is not installed")
    browser_inventory = inventory(browser_cache, exclude=False)
    playwright = json.loads((ROOT / "node_modules/playwright/package.json").read_text())["version"]
    records = [
        ("TCB-PYTHON", versions["python"], "Python runtime, standard library, unittest and runner execution"),
        ("TCB-JAVA", versions["java"] + "; " + versions["javac"], "Java VM and compiler correctness"),
        ("TCB-NODE", versions["node"], "Node runtime and browser test execution"),
        ("TCB-BASH", versions["bash"], "Build shell and ordinary host userland commands"),
        ("TCB-NETNS", versions["unshare"] + "; " + versions["ip"], "Kernel child user/network namespace and loopback enforcement"),
        ("TCB-CHROMIUM", version([str(browser), "--version"]), "Chromium engine and its runtime dependencies; cache tree pinned separately"),
        ("TCB-PLAYWRIGHT", playwright, "Playwright driver, assertions and protocol; installed package tree pinned in manifest"),
        ("TCB-ALLOY-ACGN", "Vendored files pinned by manifest", "ACGN normalization and Alloy parser semantics are trusted beyond finite assertions"),
        ("TCB-CORPUS-LABELS", "Frozen private source witnesses and classification provenance", "Corpus correct labels and oracle truth are trusted; hashes and exact context/body checks do not re-prove semantic correctness"),
        ("TCB-SHA256", ssl.OPENSSL_VERSION, "hashlib implementation and SHA-256 collision resistance"),
        ("TCB-OS", platform.platform(), "Linux, filesystem, process isolation and dynamically linked system libraries"),
        ("TCB-HARDWARE", platform.machine(), "Processor, memory and storage correctness"),
        ("TCB-TEST-INTERPRETATION", "Frozen test source hashes", "Test assertions define the finite claim interpretation; no semantic proof promotion"),
    ]
    return {"schema_version": SCHEMA, "trusted_components": [
        {"id": identifier, "classification": "TRUSTED", "version": value, "justification": reason}
        for identifier, value, reason in records], "verified_dependencies": [],
        "runtime_versions": versions, "executables": executables,
        "external_browser_cache": {"path": str(browser_cache), "files": browser_inventory,
                                   "root_hash": sha(canonical(browser_inventory))}}


def discover_ids(root):
    identifiers = []
    for path in sorted((root / "tests").glob("test_*.py")):
        tree = ast.parse(path.read_text())
        for node in tree.body:
            if isinstance(node, ast.ClassDef):
                identifiers.extend(f"{path.stem}.{node.name}.{method.name}"
                                   for method in node.body if isinstance(method, ast.FunctionDef)
                                   and method.name.startswith("test_"))
    return sorted(identifiers)


def python_tests(report_path):
    sys.path[:0] = [str(ROOT), str(ROOT / "tests")]
    outcomes = {}

    class Results(unittest.TextTestResult):
        def startTest(self, test):
            outcomes[test.id()] = "UNRESOLVED"
            super().startTest(test)

        def addSuccess(self, test):
            if outcomes[test.id()] == "UNRESOLVED": outcomes[test.id()] = "PASS"
            super().addSuccess(test)

        def addFailure(self, test, error):
            outcomes[test.id()] = "BLOCK"
            super().addFailure(test, error)

        def addError(self, test, error):
            outcomes[test.id()] = "BLOCK"
            super().addError(test, error)

        def addSkip(self, test, reason):
            outcomes[test.id()] = "BLOCK"
            super().addSkip(test, reason)

        def addSubTest(self, test, subtest, error):
            if error is not None: outcomes[test.id()] = "BLOCK"
            super().addSubTest(test, subtest, error)

        def addExpectedFailure(self, test, error):
            outcomes[test.id()] = "BLOCK"
            super().addExpectedFailure(test, error)

        def addUnexpectedSuccess(self, test):
            outcomes[test.id()] = "BLOCK"
            super().addUnexpectedSuccess(test)

    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"), pattern="test_*.py")
    result = unittest.TextTestRunner(verbosity=2, resultclass=Results).run(suite)
    report = {"tests_run": result.testsRun, "outcomes": outcomes, "successful": result.wasSuccessful()
              and all(value == "PASS" for value in outcomes.values())}
    write_json(Path(report_path), report)
    return 0 if report["successful"] else 1


def decide(state):
    """Only this deterministic rule may produce the terminal closure state."""
    if state.get("infrastructure_errors"):
        return "INFRASTRUCTURE_FAILURE"
    if state.get("blocking_reasons") or not state.get("claims"):
        return "BLOCKED"
    if any(claim != "PASS" for claim in state["claims"].values()):
        return "BLOCKED"
    if state.get("undeclared_dependencies") or state.get("unresolved_correspondence", 0):
        return "BLOCKED"
    if state.get("invalid_witnesses", 0) or state.get("passed_builds") != 2:
        return "BLOCKED"
    if not state.get("deterministic") or not state.get("provenance_complete"):
        return "BLOCKED"
    if not state.get("inputs_unchanged") or not state.get("evidence_bound"):
        return "BLOCKED"
    return "VERIFIED"


def negative_controls():
    base = {"claims": {"fixture": "PASS"}, "passed_builds": 2, "deterministic": True,
            "provenance_complete": True, "inputs_unchanged": True, "evidence_bound": True}
    observed = {"valid_fixture": decide(base)}
    for code in sorted(BLOCKING_CODES):
        mutated = deepcopy(base)
        mutated["blocking_reasons"] = [{"code": code}]
        observed[code] = decide(mutated)
    for name, patch in {
        "missing_claim_result": {"claims": {"fixture": "UNRESOLVED"}},
        "empty_claims": {"claims": {}}, "undeclared_dependency": {"undeclared_dependencies": ["x"]},
        "unresolved_correspondence": {"unresolved_correspondence": 1},
        "invalid_witness": {"invalid_witnesses": 1}, "failed_build": {"passed_builds": 1},
        "artifact_nondeterminism": {"deterministic": False}, "missing_provenance": {"provenance_complete": False},
        "input_mutation": {"inputs_unchanged": False}, "stale_evidence": {"evidence_bound": False},
    }.items():
        observed[name] = decide(dict(base, **patch))
    observed["infrastructure_precedence"] = decide(dict(base, infrastructure_errors=["missing tool"],
                                                       blocking_reasons=[{"code": "INPUT_MUTATION"}]))
    expected = {key: "BLOCKED" for key in observed}
    expected.update(valid_fixture="VERIFIED", infrastructure_precedence="INFRASTRUCTURE_FAILURE")
    return {"controls": observed, "expected": expected, "passed": observed == expected}


def check_network():
    routes = Path("/proc/net/route").read_text().splitlines()[1:]
    ipv6_routes = Path("/proc/net/ipv6_route").read_text().splitlines()
    external_v6 = [row for row in ipv6_routes if row.split()[-1] != "lo"]
    interfaces = sorted(name for _, name in socket.if_nameindex())
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
    result = {"interfaces": interfaces, "ipv4_routes": routes,
              "external_ipv6_routes": external_v6, "openai_disabled": os.environ.get("OPENAI_DISABLED") == "1"}
    result["passed"] = interfaces == ["lo"] and not routes and not external_v6 and result["openai_disabled"]
    print(json.dumps(result))
    return 0 if result["passed"] else 1


def run_command(command, cwd, log, timeout=240):
    environment = {key: value for key, value in os.environ.items()
                   if key in {"PATH", "HOME", "LANG", "LC_ALL", "DISPLAY", "PLAYWRIGHT_BROWSERS_PATH"}}
    environment.update(OPENAI_DISABLED="1", PYTHONDONTWRITEBYTECODE="1", PYTHONHASHSEED="0", TZ="UTC")
    wrapped = ["unshare", "-Urn", "sh", "-c", 'ip link set lo up && exec "$@"', "closure-offline", *command]
    start = time.monotonic()
    with log.open("xb") as output:
        process = subprocess.run(wrapped, cwd=cwd, env=environment, stdout=output,
                                 stderr=subprocess.STDOUT, timeout=timeout, check=False)
    return {"command": command, "exit_code": process.returncode, "log": str(log),
            "log_sha256": file_sha(log), "elapsed_seconds": round(time.monotonic() - start, 3)}


def execute():
    closure_id = datetime.now(timezone.utc).strftime("portal-%Y%m%dT%H%M%SZ-") + secrets.token_hex(4)
    run = ROOT / "closure" / "runs" / closure_id
    run.mkdir(parents=True, exist_ok=False)
    for directory in ("logs", "evidence", "reports"):
        (run / directory).mkdir()
    state = {"claims": {}, "passed_builds": 0, "deterministic": False, "provenance_complete": False,
             "inputs_unchanged": False, "evidence_bound": False, "blocking_reasons": [], "infrastructure_errors": []}
    root_hash, claims, tcb, provenance, builds, witnesses, registry = None, [], {}, [], [], [], {}
    policy = json.loads((ROOT / "closure/policy.json").read_text())
    entries, metadata = [], {}

    def block(code, claim=None, detail=""):
        state["blocking_reasons"].append({"code": code, "claim_id": claim, "detail": detail})

    try:
        claims = json.loads((ROOT / "closure/claims.json").read_text())["claims"]
        ids = discover_ids(ROOT)
        declared_ids = set()
        for claim in claims:
            claim["required_test_ids"] = [identifier for identifier in ids if any(
                identifier.startswith(prefix) for prefix in claim.get("test_prefixes", []))]
            declared_ids.update(claim["required_test_ids"])
            if any(not any(identifier.startswith(prefix) for identifier in ids)
                   for prefix in claim.get("test_prefixes", [])):
                block("VERIFIER_NOT_RUN", claim["id"], "Registered Python test prefix has no methods")
            claim.update(evidence_path=f"evidence/{claim['id']}.json", severity="blocking",
                         dependencies={"verified": [], "trusted": policy["required_dependencies"]},
                         pass_condition={"type": "predicate", "expression": "all_registered_checks_pass_in_both_builds"},
                         block_conditions=["any registered assertion fails", "any required test is absent or skipped", "evidence is stale or unbound"])
            state["claims"][claim["id"]] = "UNRESOLVED"
            if claim["id"] == "C-BROWSER":
                claim["required_browser_scenarios"] = re.findall(r"await check\('([^']+)'", (ROOT / "tests/browser.mjs").read_text())
                if not claim["required_browser_scenarios"]:
                    block("VERIFIER_NOT_RUN", claim["id"], "No browser scenarios declared")
        if len({c["id"] for c in claims}) != len(claims) or declared_ids != set(ids):
            block("ORPHAN_CLAIM", detail="Duplicate claims or unmapped discovered Python tests")
        correspondence = policy["correspondence"]
        if correspondence["required_objects"] != 0 or correspondence["mappings"] or policy["proof_inventory"]:
            state["unresolved_correspondence"] = max(1, correspondence["required_objects"])
            block("UNMAPPED_IMPLEMENTATION_OBJECT", detail="This closure supports zero proof objects; nonempty correspondence requires a new verifier")
        if any(claim["claim_class"] in {"implementation_correspondence", "proof_compilation"} for claim in claims):
            block("SCOPE_LEAK", detail="Proof/correspondence claims are explicitly excluded")
        if any(boundary["classification"] == "UNDEFINED" for boundary in policy["boundaries"]):
            block("UNDEFINED_BEHAVIOR_DEPENDENCY")
        # Candidate claims are frozen before inventorying the implementation.
        write_json(run / "claims.json", {"schema_version": SCHEMA, "claims": claims})
        entries = inventory(ROOT)
        input_paths = {entry["path"]: entry for entry in entries}
        required = ["scripts/build.sh", "tests/browser.mjs", "node_modules/playwright/package.json", "web/app.js"]
        missing = [path for path in required if path not in input_paths]
        if missing: raise FileNotFoundError("Required closure inputs missing: " + ", ".join(missing))
        tcb = tcb_record()
        dependencies = {item["id"] for item in tcb["trusted_components"]} | set(tcb["verified_dependencies"])
        state["undeclared_dependencies"] = sorted(set(policy["required_dependencies"]) - dependencies)
        if state["undeclared_dependencies"]: block("UNDECLARED_DEPENDENCY")
        implementations = {
            "V-PYTHON": ["scripts/verify_closure.py", "scripts/import_correct_pools.py", "scripts/import_exercises.py", "tests/test_correct_pools.py", "tests/test_catalogue.py", "tests/test_portal.py", "tests/test_adversarial.py", "tests/test_repository.py", "tests/test_credentials.py", "tests/test_runtime_dependencies.py", "tests/test_windows_build.py", "tests/test_iis_package.py", "tests/test_iis_compat.py", "tests/test_iis_launcher.py"],
            "V-ENGINE": ["scripts/verify_closure.py", "tests/test_engine_corpus.py", "tests/test_expanded_trace.py", "tests/test_nearest_correct.py", "tests/test_pool_portal.py", "engine/src/live/EngineSelfTest.java", "engine/src/is/fivefivefive/CanDis/LiveTrace.java"],
            "V-BROWSER": ["scripts/verify_closure.py", "tests/browser.mjs"],
            "V-BUILD": ["scripts/verify_closure.py", "scripts/build.sh", "scripts/build-windows.sh", "scripts/build.ps1", "engine/build.sh", "scripts/package_iis.py", "runtime_dependencies.py", "scripts/import_correct_pools.py", "scripts/import_exercises.py"],
            "V-INTEGRITY": ["scripts/verify_closure.py", "closure/policy.json"],
        }
        for identifier, paths in implementations.items():
            implementation = [{"path": path, "sha256": input_paths[path]["sha256"]} for path in paths]
            registry[identifier] = {"id": identifier, "implementation": implementation,
                                    "version_hash": sha(canonical(implementation)),
                                    "executable": "python3 scripts/verify_closure.py",
                                    "claim_classes": ["test", "provenance", "determinism"],
                                    "exit_codes": policy["exit_code_mapping"]}
        for claim in claims:
            matched = [entry["path"] for entry in entries if any(entry["path"] == p or entry["path"].startswith(p + "/")
                       for p in claim["scope"]["inputs"])]
            for public_claim in claim["public_claims"]:
                source, _, anchor = public_claim.partition("#")
                headings = re.findall(r"^## (.+)$", (ROOT / source).read_text(), re.MULTILINE) if source in input_paths else []
                heading_anchors = [re.sub(r"[^\w -]", "", h.casefold()).replace(" ", "-") for h in headings]
                if source not in input_paths or (anchor and anchor.casefold() not in heading_anchors):
                    block("ORPHAN_CLAIM", claim["id"], "Public claim target missing")
                provenance.append({"public_claim": public_claim, "claim_id": claim["id"],
                    "evidence": claim["evidence_path"], "verifier": claim["verifier"], "inputs": matched,
                    "trusted_dependencies": policy["required_dependencies"]})
            for path in claim["witnesses"]:
                if path not in input_paths:
                    block("MISSING_WITNESS", claim["id"], path)
                witnesses.append({"claim_id": claim["id"], "path": path,
                                  "sha256": input_paths.get(path, {}).get("sha256")})
        config = {"schema_version": SCHEMA, "closure_id": closure_id, **policy,
                  "claim_file": "claims.json", "tcb_file": "tcb.json", "manifest_file": "manifest.json",
                  "provenance_file": "provenance.json", "verifier_registry": "verifier-registry.json",
                  "source_exclusions": {"directories": sorted(SKIP_DIRS | SKIP_PATHS), "private_files": [".env", ".env.* except .env.example", "openai.key", "openai.local.json"],
                                        "generated_files": ["*.pyc"]}}
        for name, value in {"closure-config.json": config, "tcb.json": tcb,
                            "provenance.json": provenance, "verifier-registry.json": registry,
                            "witnesses.json": witnesses, "correspondence.json": policy["correspondence"]}.items():
            write_json(run / name, value)
        metadata = {path.name: file_sha(path) for path in sorted(run.glob("*.json"))}
        manifest = {"schema_version": SCHEMA, "files": entries,
                    "frozen_metadata": [{"path": name, "sha256": digest} for name, digest in metadata.items()],
                    "external_trusted_inputs": [{"path": tcb["external_browser_cache"]["path"],
                                                 "tree_sha256": tcb["external_browser_cache"]["root_hash"]}]}
        write_json(run / "manifest.json", manifest)
        root_hash = file_sha(run / "manifest.json")
        copy_inputs(ROOT, run / "inputs", entries)
        if inventory(run / "inputs") != entries: block("INPUT_MUTATION", detail="Frozen snapshot differs")
        controls = negative_controls()
        write_json(run / "evidence/negative-controls.json", {"closure_id": closure_id, "input_root_hash": root_hash,
                   "claim_id": "C-INTEGRITY", "schema_version": SCHEMA,
                   "verifier_id": "V-INTEGRITY", "verifier_hash": registry["V-INTEGRITY"]["version_hash"],
                   "execution_environment": {"os": platform.platform(), "runtime": tcb["runtime_versions"]},
                   "raw_result": controls, "exit_code": 0 if controls["passed"] else 1,
                   "status": "PASS" if controls["passed"] else "BLOCK"})
        if not controls["passed"]: block("VERIFIER_FAILURE", "C-INTEGRITY", "Negative controls failed")
        if state["blocking_reasons"]: raise RuntimeError("FROZEN_BOUNDARY_BLOCKED")
        for number in (1, 2):
            name = f"build-{number}"
            print(f"{closure_id}: {name}: copying and building frozen inputs", flush=True)
            work = run / name
            copy_inputs(run / "inputs", work, entries)
            if inventory(work) != entries:
                block("INPUT_MUTATION", detail=f"{name} initial copy mismatch")
                break
            record = {"id": name, "input_root_hash": root_hash, "checks": {}}
            commands = {
                "network": [sys.executable, "scripts/verify_closure.py", "--network-check"],
                "build": ["bash", "scripts/build.sh"],
                "package": [sys.executable, "scripts/package_iis.py"],
                "engine": ["java", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp", "build/engine/classes:vendor/acgn/lib/*", "live.EngineSelfTest"],
                "python": [sys.executable, "scripts/verify_closure.py", "--unittest-report", str(run / "logs" / f"{name}-python.json")],
                "browser": ["node", "tests/browser.mjs"],
            }
            for check, command in commands.items():
                print(f"{closure_id}: {name}: {check}", flush=True)
                result = run_command(command, work, run / "logs" / f"{name}-{check}.log")
                result["log"] = str(Path(result["log"]).relative_to(run))
                record["checks"][check] = result
                if result["exit_code"] != 0:
                    if check == "network": raise OSError("Child network namespace or offline check unavailable")
                    if result["exit_code"] != 1:
                        raise OSError(f"Registered verifier {check} exited with infrastructure code {result['exit_code']}: {result['log']}")
                    code = "CLEAN_BUILD_FAILURE" if check in ("build", "package") else "VERIFIER_FAILURE"
                    block(code, {"build": "C-BUILDS", "package": "C-IIS", "engine": "C-ENGINE", "python": None, "browser": "C-BROWSER"}.get(check), result["log"])
                    break
            if all(record["checks"].get(check, {}).get("exit_code") == 0 for check in ("build", "package")):
                state["passed_builds"] += 1
            python_report = run / "logs" / f"{name}-python.json"
            record["python"] = json.loads(python_report.read_text()) if python_report.is_file() else {"outcomes": {}}
            browser_log = run / "logs" / f"{name}-browser.log"
            record["browser"] = {}
            if browser_log.is_file():
                for line in browser_log.read_text().splitlines():
                    try:
                        candidate = json.loads(line)
                        if isinstance(candidate, dict) and "passed" in candidate: record["browser"] = candidate
                    except ValueError:
                        pass
            record["artifacts"] = [{"path": str(path.relative_to(work)), "sha256": file_sha(path)}
                for folder in (work / "build/engine/classes", work / "web")
                for path in sorted(folder.rglob("*")) if path.is_file() and (folder.name == "web" or path.suffix == ".class")]
            for relative in ("build/iis/alloy-studio-iis.zip", "build/iis/alloy-studio-iis.zip.sha256"):
                artifact = work / relative
                if artifact.is_file():
                    record["artifacts"].append({"path": relative, "sha256": file_sha(artifact)})
                else:
                    block("CLEAN_BUILD_FAILURE", "C-BUILDS", "Missing deterministic artifact: " + relative)
            record["proof_inventory"] = policy["proof_inventory"]
            record["correspondence"] = policy["correspondence"]
            record["provenance"] = provenance
            record["claim_statuses"] = {}
            for claim in claims:
                identifier = claim["id"]
                methods = claim["required_test_ids"]
                if methods:
                    passed = all(record["python"]["outcomes"].get(method) == "PASS" for method in methods)
                    if identifier == "C-ENGINE":
                        passed &= record["checks"].get("engine", {}).get("exit_code") == 0
                        engine_log = run / "logs" / f"{name}-engine.log"
                        marker = f"EngineSelfTest passed ({claim['required_engine_checks']} checks)"
                        passed &= engine_log.is_file() and marker in engine_log.read_text()
                    if identifier == "C-IIS":
                        passed &= record["checks"].get("package", {}).get("exit_code") == 0
                    record["claim_statuses"][identifier] = "PASS" if passed else "BLOCK"
                elif identifier == "C-BROWSER":
                    passed = record["checks"].get("browser", {}).get("exit_code") == 0
                    passed &= record["browser"].get("status") == "PASS"
                    passed &= record["browser"].get("passed") == claim["required_browser_scenarios"]
                    passed &= record["browser"].get("checks") == len(claim["required_browser_scenarios"])
                    record["claim_statuses"][identifier] = "PASS" if passed else "BLOCK"
                elif identifier == "C-BUILDS":
                    record["claim_statuses"][identifier] = "PASS" if all(record["checks"].get(check, {}).get("exit_code") == 0 for check in ("build", "package")) else "BLOCK"
                else:
                    record["claim_statuses"][identifier] = "PASS" if controls["passed"] and record["checks"].get("network", {}).get("exit_code") == 0 else "BLOCK"
            # Browser exports/log artifacts are not source inputs. Only the frozen
            # declared files are compared here; original-tree additions are caught below.
            for entry in entries:
                path = work / entry["path"]
                actual = sha(os.readlink(path).encode()) if entry["kind"] == "symlink" else file_sha(path)
                if actual != entry["sha256"]: block("INPUT_MUTATION", detail=f"{name}:{entry['path']}")
            write_json(run / "evidence" / f"{name}.json", record)
            builds.append(record)
            if state["blocking_reasons"]: break
        if len(builds) == 2:
            comparable = ("artifacts", "proof_inventory", "correspondence", "provenance", "claim_statuses")
            state["deterministic"] = all(builds[0][key] == builds[1][key] for key in comparable)
            if not state["deterministic"]: block("NONDETERMINISM", "C-BUILDS")
        for claim in claims:
            identifier = claim["id"]
            passed = len(builds) == 2 and all(b["claim_statuses"].get(identifier) == "PASS" for b in builds)
            if identifier == "C-BUILDS": passed &= state["deterministic"]
            state["claims"][identifier] = "PASS" if passed else "BLOCK"
        state["inputs_unchanged"] = inventory(ROOT) == entries and inventory(run / "inputs") == entries
        if not state["inputs_unchanged"]: block("INPUT_MUTATION")
        for name, expected in metadata.items():
            if file_sha(run / name) != expected: block("CLAIM_MUTATION" if name == "claims.json" else "INPUT_MUTATION", detail=name)
        if file_sha(run / "manifest.json") != root_hash: block("INPUT_MUTATION", detail="manifest.json")
        external = tcb["external_browser_cache"]
        if inventory(Path(external["path"]), exclude=False) != external["files"]:
            block("INPUT_MUTATION", detail="External browser cache changed")
        state["provenance_complete"] = len(provenance) == sum(len(c["public_claims"]) for c in claims) and all(
            item["inputs"] and item["verifier"] in registry and item["trusted_dependencies"] for item in provenance)
        if not state["provenance_complete"]: block("ORPHAN_CLAIM")
        for witness in witnesses:
            witness["valid"] = bool(witness["sha256"]) and state["claims"].get(witness["claim_id"]) == "PASS"
        state["invalid_witnesses"] = sum(not witness["valid"] for witness in witnesses)
        if state["invalid_witnesses"]: block("WITNESS_INVALID")
        state["evidence_bound"] = True
        if state["blocking_reasons"]: state["claims"]["C-INTEGRITY"] = "BLOCK"
        for claim in claims:
            identifier, verifier = claim["id"], registry[claim["verifier"]]
            evidence = {"schema_version": SCHEMA, "claim_id": identifier, "closure_id": closure_id,
                "input_root_hash": root_hash, "verifier_id": verifier["id"], "verifier_hash": verifier["version_hash"],
                "execution_environment": {"os": platform.platform(), "runtime": tcb["runtime_versions"], "network": policy["network"]},
                "raw_result": {"required_test_ids": claim["required_test_ids"],
                    "negative_controls": {"path": "evidence/negative-controls.json", "sha256": file_sha(run / "evidence/negative-controls.json")} if identifier == "C-INTEGRITY" else None,
                    "build_results": [{"id": b["id"], "status": b["claim_statuses"][identifier],
                        "evidence": f"evidence/{b['id']}.json", "sha256": file_sha(run / "evidence" / f"{b['id']}.json")} for b in builds],
                    "witnesses": [w for w in witnesses if w["claim_id"] == identifier],
                    "checks": {key: state[key] for key in ("inputs_unchanged", "provenance_complete", "deterministic")}},
                "exit_code": 0 if state["claims"][identifier] == "PASS" else 1, "status": state["claims"][identifier]}
            write_json(run / claim["evidence_path"], evidence)
            stored = json.loads((run / claim["evidence_path"]).read_text())
            if (stored["input_root_hash"] != root_hash or stored["verifier_hash"] != verifier["version_hash"] or
                stored["closure_id"] != closure_id or stored["claim_id"] != identifier):
                state["evidence_bound"] = False
                block("STALE_OR_UNBOUND_EVIDENCE", identifier)
        for identifier, status in state["claims"].items():
            if status != "PASS": block("VERIFIER_NOT_RUN" if status == "UNRESOLVED" else "VERIFIER_FAILURE", identifier)
    except RuntimeError as error:
        if str(error) != "FROZEN_BOUNDARY_BLOCKED":
            state["infrastructure_errors"].append({"code": "INFRASTRUCTURE_ERROR", "operation": "closure execution", "detail": str(error)})
    except (OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
        state["infrastructure_errors"].append({"code": "INFRASTRUCTURE_ERROR", "operation": "closure execution", "detail": f"{type(error).__name__}: {error}"})
    status = decide(state)
    report = {"schema_version": SCHEMA, "closure_id": closure_id, "timestamp": datetime.now(timezone.utc).isoformat(),
        "status": status, "input_root_hash": root_hash,
        "claims": {"total": len(claims), "passed": sum(v == "PASS" for v in state["claims"].values()),
                   "blocked": sum(v == "BLOCK" for v in state["claims"].values()),
                   "unresolved": sum(v == "UNRESOLVED" for v in state["claims"].values()), "statuses": state["claims"]},
        "correspondence": {"required_objects": policy["correspondence"]["required_objects"],
                           "mapped_objects": 0,
                           "unmapped_objects": policy["correspondence"]["required_objects"],
                           "ambiguous_objects": 0,
                           "interpretation": "No proof correspondence is claimed"},
        "witnesses": {"required": len(witnesses), "valid": sum(w.get("valid", False) for w in witnesses),
                      "invalid": sum(not w.get("valid", False) for w in witnesses)},
        "builds": {"required": 2, "passed": state["passed_builds"], "records": [f"evidence/{b['id']}.json" for b in builds]},
        "determinism": {"required": True, "status": "PASS" if state["deterministic"] else "BLOCK"},
        "provenance": {"public_claims": sum(len(c["public_claims"]) for c in claims),
                       "fully_bound": len(provenance) if state["provenance_complete"] else 0,
                       "orphan_claims": 0 if state["provenance_complete"] else sum(len(c["public_claims"]) for c in claims)},
        "dependencies": {"verified": tcb.get("verified_dependencies", []),
                         "trusted": [r["id"] for r in tcb.get("trusted_components", [])],
                         "undeclared": state.get("undeclared_dependencies", [])},
        "blocking_reasons": state["blocking_reasons"], "infrastructure_errors": state["infrastructure_errors"],
        "closure_boundary": {"verified_surface": [c["id"] for c in claims if state["claims"].get(c["id"]) == "PASS"],
             "trusted_surface": [r["justification"] for r in tcb.get("trusted_components", [])],
             "excluded_surface": [b["surface"] for b in policy["boundaries"] if b["classification"] == "OUT_OF_SCOPE"],
             "interpretation": "VERIFIED applies only to the frozen finite surface under the declared TCB; it is not universal correctness.",
             "evidence_kinds": {"PROVED": [], "TESTED": [c["id"] for c in claims if c["claim_class"] == "test"],
                                "CHECKED": ["C-BUILDS", "C-INTEGRITY"], "ASSUMED": [], "TRUSTED": [r["id"] for r in tcb.get("trusted_components", [])],
                                "OUT_OF_SCOPE": [b["surface"] for b in policy["boundaries"] if b["classification"] == "OUT_OF_SCOPE"]}},
        "decision": {"valid_states": ["VERIFIED", "BLOCKED", "INFRASTRUCTURE_FAILURE"], "manual_override_allowed": False}}
    write_json(run / "reports/closure-report.json", report)
    rendering = f"# {status}\n\nClosure: `{closure_id}`\n\nInput root: `{root_hash}`\n\n" + \
        f"Claims passed: {report['claims']['passed']}/{len(claims)}. Clean builds: {state['passed_builds']}/2. " + \
        f"Determinism: {report['determinism']['status']}. Correspondence: zero required objects; no proof claim.\n\n" + \
        report["closure_boundary"]["interpretation"] + "\n\nLive OpenAI availability, quota and AI explanation truth are OUT_OF_SCOPE.\n\n" + \
        "The adjacent JSON report contains authoritative evidence references, trust, counts and failure reasons.\n"
    (run / "reports/closure-report.md").write_text(rendering)
    # Chmod prevents accidental overwrites; filesystem-owner/root tampering is
    # within the declared OS trust boundary and invalidates all recorded hashes.
    for directory, folders, files in os.walk(run, topdown=False):
        for name in files:
            path = Path(directory) / name
            if not path.is_symlink(): path.chmod(path.stat().st_mode & ~0o222)
        Path(directory).chmod(Path(directory).stat().st_mode & ~0o222)
    print(json.dumps({"status": status, "closure_id": closure_id, "input_root_hash": root_hash,
                      "report": str(run / "reports/closure-report.json")}))
    return {"VERIFIED": 0, "BLOCKED": 1, "INFRASTRUCTURE_FAILURE": 2}[status]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--network-check", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--unittest-report", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.self_test:
        result = negative_controls()
        print(json.dumps(result, sort_keys=True))
        return 0 if result["passed"] else 1
    if args.network_check: return check_network()
    if args.unittest_report: return python_tests(args.unittest_report)
    return execute()


if __name__ == "__main__":
    raise SystemExit(main())
