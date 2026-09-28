# CI/CD dashboard

Open **`/dashboard/`** on an Alloy Studio installation. The page shows the installed version, recorded test counts, historical mechanical closure, Lean obligations, public GitHub workflow runs, and releases. **Refresh GitHub status** reads public metadata anonymously. It does not use your OpenAI key or request a GitHub token. No remote request occurs before you press refresh. A blocked network or GitHub rate limit is shown as unavailable, never as success.

The shipped `web/dashboard/data.json` is an honest baseline: check results are not recorded. An earlier successful closure is labeled historical and applies only to its frozen inputs. It never establishes that a newer deployed build is verified. Local check results show their revision and remain unbound when the checkout has uncommitted changes or the revision cannot be identified. Workflow success likewise does not assert that production was deployed.

## Pipelines

`.github/workflows/ci.yml` runs on pushes to `master`, release tags, pull requests, and manual dispatch:

- Linux: fresh portable build, engine self-tests, complete Python regressions, portal browser scenarios, and dashboard browser scenarios.
- Windows and macOS: the same portable build entrypoint and runtime dependency/engine smoke test. These jobs do not install or exercise IIS itself.
- Every job produces a dashboard artifact containing exactly HTML, JavaScript, CSS, and an allowlisted JSON summary. No private IIS ZIP, exercise files, raw closure evidence, logs, or credentials are uploaded.

`.github/workflows/release.yml` checks tag/package version agreement and builds and validates the engine for a tagged source release. It produces a readiness dashboard; publication of a GitHub release and deployment to IIS remain explicit maintainer steps. It has no write token or server credentials. CI results are recorded tests, not the two-build offline mechanical closure. Run `OPENAI_DISABLED=1 python3 scripts/verify_closure.py` on a host that supports the repository’s network-namespace closure protocol for that evidence.

Actions use full commit pins, read-only repository permissions, and no persisted checkout credentials. All test commands run with `OPENAI_DISABLED=1`. Failure output can contain a model in an assertion, so the CI wrapper publishes only check names, status, counts, and revision. Reproduce a failed command locally to inspect its full output.

## Generate a local snapshot

After installing the normal developer prerequisites (`npm ci`, Playwright Chromium, Python 3.10+, JDK 17+, and Node), run from the repository root:

```bash
python3 scripts/ci_check.py build
python3 scripts/ci_check.py runtime
python3 scripts/ci_check.py python
python3 scripts/ci_check.py browser
python3 scripts/ci_check.py dashboard
python3 scripts/ci_dashboard.py
python3 -m http.server 8090 --bind 127.0.0.1 --directory build/ci-dashboard
```

Open `http://127.0.0.1:8090/`. The generator reads only fixed report locations and exports an allowlist of status fields; it does not publish arbitrary report text. Use `--output` for another output directory. To include a snapshot in an IIS build, explicitly copy its `data.json` to `web/dashboard/data.json` **before** building and freezing verification inputs. That file becomes a new build input. The normal packager never scans your Git metadata or old closure directories to silently alter an archive.

Dashboard links use relative paths, so `/alloy/dashboard/` works under an IIS application prefix. Its only external connection is `https://api.github.com`; allow that origin in the dashboard’s `connect-src` policy. The learning portal needs no external browser API access.

GitHub documents [public workflow-run metadata](https://docs.github.com/en/rest/actions/workflow-runs), [release metadata](https://docs.github.com/en/rest/releases/releases), and [workflow token permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions). Artifact downloads may require a GitHub login; the dashboard links to the public run page instead of claiming anonymous artifact download is available.
