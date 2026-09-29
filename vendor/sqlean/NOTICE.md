# SQLeanParser source snapshot

This directory contains the original parser/library source, CLI entry point,
README and build/toolchain descriptors from SQLeanParser commit
`6da54ef2874cbe7e0069bf8c192f12de3a8644f9`. File hashes and the executable used
to generate the portal's SQL artifacts are recorded in `provenance.json`.
Original source contents have not been modified.

The upstream checkout supplied for this integration did not contain a LICENSE
or COPYING file. This provenance notice does not add or alter license terms.

The source is included for review and explicit regeneration. The portal runtime
does not compile it, load a Lean toolchain or execute the parser. The upstream
validation proofs use standard Lean axioms and do not prove SQLite execution;
they are separate from the portal's empty-axiom Lean proof blocks.
