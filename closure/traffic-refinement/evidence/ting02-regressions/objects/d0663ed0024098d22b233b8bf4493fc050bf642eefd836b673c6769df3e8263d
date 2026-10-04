# B01 tier 2 review — Sol A

I read both Luna reviews before reviewing all four frozen B01 sources. Their JSON files have the same SHA-256, `97de34891f650081a4df1bb1d6665fcc98fb0848ee90d6f69576e6b7c2947c19`; the manifest hash is `ff09b3994b359540d1fc5ffdf6df7a4c9f365a14790d3f236d2b93ad27915ca9`. All four source hashes agree with the manifest. I found no constructed breach of its claims.

The main adversarial checks concerned boundary behavior, occurrence order, inverse scripts, and cost meaning. `splitRoots` fails explicitly past the sibling boundary. Adopting two roots into one parent and deleting that parent restores both roots in order. The same transformation inside a real ancestor changes the postorder occurrence list from `[1, 2, 7]` to `[1, 2, 9, 7]`; relabeling the inserted node changes no occurrence IDs. A concrete insertion followed by deletion gives a two-step script from a forest to itself, and contextual inversion gives a one-step script in the opposite direction. This confirms that `Script` cost counts edit steps; it is not asserted to be a minimum distance. A relabel with equal labels can similarly be a charged step, which is consistent with the constructor's unit-cost semantics.

The probe also shows that inserting identifier `1` into a forest already containing identifier `1` succeeds and produces occurrences `[1, 1, 2]`. This is the explicit freshness exclusion in B01, not a false uniqueness theorem: `promotion_preserves_unique_occurrences` assumes uniqueness of its adopted input. The same distinction applies to Java refinement, parser and adapter behavior, and semantic distance optimality, which the manifest excludes. The theorem statements about closure counts and finite-pool selection are conditional on supplied data and evaluator behavior, respectively; neither makes an external evidence-truthfulness claim.

Executable evidence: `build/b01-sol-a/probe.lean` (SHA-256 `d19ba48c97bbb12b0ad8fde5954af33e416a05148c0d9945d1d9561f12223d7c`) passed with exit code 0 using:

```sh
python3 scripts/lean_offline.py --cwd formal --lean-path /home/augustus/Live_Programming/build/lean-block-check-strict -- ../build/b01-sol-a/probe.lean --trust=0 -DwarningAsError=true -DgenInjectivity=false -j1
```

This review is advisory. The registered kernel and mechanical checks, rather than this review, determine whether B01's proof obligations close.
