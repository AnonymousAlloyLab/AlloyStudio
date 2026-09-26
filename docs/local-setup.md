# Run a cloned checkout on Linux or macOS

The portal runs locally with Python 3.10+, a JDK 17+ containing both `java` and
`javac`, and Bash. Its Python server uses only the standard library. All seven
Java dependency JARs, including AlloyASG, AlloyParser, Alloy, and JSON, are in
`vendor/acgn/lib`. Node, npm, pip packages, IIS, and a separate ACGN code checkout
are unnecessary for local setup and use.

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

The oracle-bearing catalogue and correct pools are private. A public Git clone
cannot recreate them without one of the following inputs. Obtain the trusted
`alloy-studio-iis.zip` privately from the project owner and run:

```bash
./scripts/setup.sh --from-bundle "/path/to/alloy-studio-iis.zip"
```

This works on Linux and macOS despite the archive's IIS name. Setup reads only
the two exercise-data members, verifies their manifest hashes and source
witnesses, and writes private `exercises/catalogue.json` and
`exercises/correct-pools.json`. It does not extract the archive's other files or
copy another deployment's credentials.

Alternatively, if you have the original ACGN checkout containing
`classified-data/`, use:

```bash
./scripts/setup.sh --source-root "/path/to/original/ACGN"
```

`ACGN_ROOT` can also select that original corpus. The bundled `vendor/acgn`
directory supplies engine code and libraries; it is not a substitute for
`classified-data/`. If both private exercise files are already installed, run
`./scripts/setup.sh` without a source option. Valid existing files are preserved
and need neither the original corpus nor the ZIP for subsequent launches.

Setup checks dependency hashes, compiles from the bundled source using Java 17
bytecode, and runs all 372 engine checks. A failure stops startup. Relative
bundle, corpus, and JDK paths are relative to the terminal's current directory;
you may invoke the scripts from outside the checkout.

## Start and stop

```bash
./scripts/run.sh
```

Open **http://127.0.0.1:8080**. The server stays in the terminal; Ctrl+C stops it.
Startup repeats data/dependency checks, compilation, and engine tests, so source
updates are built before serving requests. A first launch can also prepare data
with `./scripts/run.sh --from-bundle "/path/to/alloy-studio-iis.zip"`.

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
| `SOURCE_CORPUS_MISSING` | Supply the private ZIP with `--from-bundle` or original corpus with `--source-root`. |
| Partial or invalid private data | Restore both matching private files; setup preserves existing data and reports the diagnostic code. |
| Dependency missing or hash mismatch | Restore the complete checkout including all seven JARs in `vendor/acgn/lib`; do not copy source directories alone. |
| Address already in use | Choose another `--port`, or stop the process using that port. |
| OpenAI unavailable | Deterministic feedback still works; check your private configuration and account access. |

The local launcher deliberately clears ambient Java classpaths and Java option
injection variables for its child processes. Python is launched with `-E -s`
to avoid ambient Python import paths and user site packages. Neither launcher
downloads dependencies nor installs system software automatically.

Linux regression tests launch a fresh source tree from a path containing
spaces, restore a private data fixture, build without Node or the original
ACGN directory, request real JVM feedback over HTTP, and stop the server.
macOS JDK discovery and failure handling are exercised with simulated fixtures
on Linux. Native macOS execution, including Apple silicon, has not been tested
in this environment. On a Mac, successful setup's engine checks followed by an
editor feedback request are the local acceptance check.

For the developer and release test suite, see [Checks and closure](../README.md#checks-and-closure).
Those additional checks require Node and browser tooling; ordinary local use
does not.
