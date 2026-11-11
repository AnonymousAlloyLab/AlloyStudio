# Java dependencies

The active runtime uses Alloy 6.2.0, Commons CLI 1.11.0, JSON-java 20260814,
and SLF4J API/simple 2.0.20. `dependencies.json` records upstream Maven URLs,
upstream SHA-256 digests, and the exact installed JAR digests.

Refresh the pinned artifacts with builds stopped:

```sh
python3 scripts/refresh_java_dependencies.py
```

The script verifies Maven repository checksums before replacing libraries. It
removes independently pinned CLI/JSON/SLF4J classes from the Alloy distribution
to leave one active implementation of each dependency. Other Alloy-bundled
components remain at the versions shipped and tested with Alloy 6.2.0.

`AlloyASG-Release.jar` is a dependency-free extraction of the repository's local
ASG implementation. Its previous fat-JAR dependency copies included Alloy 6.1
and would shadow newer libraries. `AlloyASG.jar` is the existing source archive;
`AlloyParser.jar` is the existing local parser adapter. These local components
have no independently versioned upstream release selected by this refresh.

The parser's `ModelUnit` and `AlloyUtil` source overlays under `src/parser/`
adapt its binary API to Alloy 6.2.0. They were derived from the corresponding
files in the author's local AlloyParser source checkout, retaining the original
behavior except for module/assertion API adaptation and SATFactory-based solver
availability. Compile all of `src`, and put compiled classes **before** `lib/*`
on the runtime classpath, as the supplied runners do. Do not use the old parser
binary alone against the new runtime.

`DependencyCompatibilityTest` checks module, predicate, function and assertion
conversion, temporal SAT/check execution, solver lookup, and duplicate dependency
classes. The dependency changes do not update archived experiment JARs or results.
