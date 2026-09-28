# Run a cloned checkout on Linux or macOS

The portal runs locally with Python 3.10+, a JDK 17+ containing both `java` and
`javac`, and Bash. Its Python server uses only the standard library. All seven
Java dependency JARs, including AlloyASG, AlloyParser, Alloy, and JSON, are in
`vendor/acgn/lib`. The exercise catalogue and correct pools are also included.
Node, npm, pip packages, IIS, an IIS ZIP, and a separate ACGN checkout are
unnecessary for local setup and use.

## Prerequisites

Install Git, Python 3.10 or newer, and a JDK 17 or newer appropriate for your
operating system and processor. On Linux, use your distribution's packages or
your JDK provider's installer. The [JDK Linux installation guide](https://docs.oracle.com/en/java/javase/17/install/installation-jdk-linux-platforms.html)
describes installation layouts and supported package types.

On macOS, the [Python installation guide](https://docs.python.org/3/using/mac.html)
describes the signed Python installer and its certificate setup step. Leave
Apple's system Python in place; it may be too old for this application. Choose
a JDK for Apple silicon (ARM64/aarch64) or Intel (x64), as appropriate. The
[JDK macOS guide](https://docs.oracle.com/en/java/javase/17/install/installation-jdk-macos.html)
describes installation and `/usr/libexec/java_home` discovery.

The launcher selects a JDK in this order: `--java-home` or `--java`, `JAVA_HOME`,
macOS `java_home`, then the JDK containing `javac` on `PATH`. It verifies that
the runtime and compiler both work, have matching major versions, and are at
least version 17. An explicitly selected but broken JDK produces an error.

For a non-default installation, set paths in your terminal before running:

```bash
export ALLOY_PYTHON="/absolute/path/to/python3"
export JAVA_HOME="/absolute/path/to/jdk"
```

These overrides are optional. `JAVA_HOME` names the directory containing `bin`,
not the `bin` directory itself. `ALLOY_PYTHON` names one executable, without
extra flags. Paths containing spaces work when quoted.

## Clone and prepare

Clone this repository using its Git URL, then change into the checkout. For
example, replace `REPOSITORY_URL` below with that URL:

```bash
git clone REPOSITORY_URL AlloyStudio
cd AlloyStudio
```

Start directly from the clone:

```bash
./scripts/run.sh
```

Open **http://127.0.0.1:8080**. To prepare and check the checkout without starting
the server, run `./scripts/setup.sh` without flags instead.

Both commands validate the bundled exercise data and dependency hashes, compile
from the bundled source using Java 17 bytecode, and run all 378 engine checks.
A failure stops startup. Relative JDK paths are relative to the terminal's
current directory; you may invoke the scripts from outside the checkout.

The tracked `exercises/catalogue.json` and `exercises/correct-pools.json` contain
181 exercises, 7,550 corpus candidates, and 181 oracle candidates. Their source
witnesses support validation without the original corpus. The browser hides
reference solutions, but anyone reading or cloning this public repository can
inspect them in these files. See [exercise data and provenance](../exercises/README.md).
API keys and local credential files remain private.

## Restore or import exercise data

If the bundled data are missing or damaged, preserve any intentional local
data edits, then restore the matching pair from the current Git revision:

```bash
git restore --source=HEAD -- exercises/catalogue.json exercises/correct-pools.json
./scripts/setup.sh
```

For a custom corpus or a legacy checkout without tracked data, setup can import
the original ACGN `classified-data/` directory or restore a trusted IIS archive:

```bash
./scripts/setup.sh --source-root "/path/to/original/ACGN"
# Or restore a matching data pair from an existing deployment archive:
./scripts/setup.sh --from-bundle "/path/to/alloy-studio-iis.zip"
```

`ACGN_ROOT` can also select the original corpus. These options preserve a valid
existing data pair; to import a different corpus, first back up and move both
existing files out of `exercises`. Setup reports partial or invalid pairs and
preserves them for recovery. The bundled `vendor/acgn` supplies engine code and
libraries, rather than the original `classified-data/` corpus.

Archive recovery works on Linux and macOS despite the archive's IIS name. It
reads only the two exercise-data members, verifies manifest hashes and source
witnesses, and copies no application files or credentials. Relative bundle and
corpus paths are relative to the terminal's current directory. Neither optional
source is required for a fresh clone or subsequent launches.

## Start and stop

```bash
./scripts/run.sh
```

Open **http://127.0.0.1:8080**. The server stays in the terminal; Ctrl+C stops it.
Startup repeats data/dependency checks, compilation, and engine tests, so source
updates are built before serving requests.

These local launchers compile the engine without creating an IIS archive. The
separate `./scripts/build.sh` developer/release command also checks the frontend
with Node and refreshes `build/iis/alloy-studio-iis.zip` and its checksum after a
successful build. Packaging on its own with `python3 scripts/package_iis.py`
also compiles Java afresh through `scripts/build_engine.py`; it requires a JDK,
while the Node check belongs to the portal build wrapper. A failed build leaves
any previous ZIP as an older artifact. See [IIS build options](../README.md#iis-100-deployment)
for compiler, output, and alternate-checkout options.

If port 8080 is occupied, use:

```bash
./scripts/run.sh --port 8081
```

Then open http://127.0.0.1:8081. Other server options include `--timeout 12`,
`--workers 4`, `--host`, and repeatable `--public-origin`. The default address
is accessible only on your own computer. These local commands do not configure
a production proxy or an operating-system service.

## Optional OpenAI configuration

Deterministic feedback works without an OpenAI key. To enable the configured
Luna integration, create the private config once for this checkout:

```bash
install -m 600 openai.example.json openai.local.json
```

Edit `openai.local.json` locally and set its `api_key` to your own key. If the
file already exists, edit it directly rather than overwriting it with the
example. Keep this file private; it is ignored by Git and excluded from release
packages and public HTTP routes. The portal has no API-key entry field. See
[the configuration reference](../README.md#gpt-6-luna) for a separate private
key file and configuration precedence.

## Troubleshooting and checks

| Failure | Action |
| --- | --- |
| Python 3.10+ required | Install a current Python or set `ALLOY_PYTHON` to it. |
| JDK missing, too old, or unusable | Install a complete JDK and set `JAVA_HOME` or pass `--java-home`. A JRE is insufficient for a source clone. |
| `SOURCE_CORPUS_MISSING` | Restore the bundled data pair from Git using the command above. Legacy/custom checkouts can supply `--from-bundle` or `--source-root`. |
| `PARTIAL_PRIVATE_DATA` or `PRIVATE_DATA_INVALID` | Restore both matching data files from Git after preserving local edits. These legacy diagnostic names refer to exercise data; setup leaves existing files untouched. |
| Dependency missing or hash mismatch | Restore the complete checkout including all seven JARs in `vendor/acgn/lib`; do not copy source directories alone. |
| Address already in use | Choose another `--port`, or stop the process using that port. |
| OpenAI unavailable | Deterministic feedback still works; check your private configuration and account access. |

The local launcher deliberately clears ambient Java classpaths and Java option
injection variables for its child processes. Python is launched with `-E -s`
to avoid ambient Python import paths and user site packages. Neither launcher
downloads dependencies nor installs system software automatically.

Linux regression tests launch source trees from paths containing spaces, build
without Node or the original ACGN directory, request real JVM feedback over
HTTP, and stop the server. Optional archive restoration is also covered.
macOS JDK discovery and failure handling are exercised with simulated fixtures
on Linux. Native macOS execution, including Apple silicon, has not been tested
in this environment. On a Mac, successful setup's engine checks followed by an
editor feedback request are the local acceptance check.

For the developer and release test suite, see [Checks and closure](../README.md#checks-and-closure).
Those additional checks require Node and browser tooling; ordinary local use
does not.
