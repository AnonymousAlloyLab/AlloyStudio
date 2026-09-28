# B01 tier 3 adversarial review — Astra A

Verdict: `no_constructed_breach`. I read the B01 manifest, all four frozen sources, and the JSON reports and adjacent notes of Luna A, Luna B, Sol A, and Sol B. All four frozen source hashes match the manifest. The report binds the manifest and all four lower-tier JSON files by SHA-256. This is advisory adversarial review, not theorem authority or an implementation-closure verdict.

The principal attack was whether ordered edit and occurrence statements lose their meaning through empty domains, impossible premises, duplicate identifiers, or a cost that does not count edits. The executable probe constructs a nonempty forest with siblings on both sides of a parent, two adopted children, and two nested real ancestors. Deleting and reinserting the parent preserves the exact sibling order; the context removes exactly one occurrence and one counted node. Both contextual forests satisfy explicit uniqueness, so the preservation premises have concrete inhabitants. The probe constructs forward and inverse one-step scripts and a two-step round trip. It also eliminates an arbitrary zero-cost script to endpoint equality. Thus the indexed cost has nontrivial meaning as the number of constructors; an equal-label relabel is still a charged step, as the source permits. No minimum-distance claim follows or is advertised.

The successful contextual postorders are `[0,6,1,2,3,4,5,8,7,9,10]` and `[0,6,1,2,3,5,8,7,9,10]`: a contextual `after` forest is sibling material following that context's parent, not children preceding that parent's postorder token. Initial probe expectations mistakenly placed the inner parent's token after its following sibling and used an unequal label in an end-insertion expected value; Lean rejected those expectations. The corrected examples agree with the source's explicit `adopt` definition. These were probe-author mistakes, not invariant breaches.

Boundary probes confirm insertion at the final sibling boundary with zero children, rejection past that boundary, rejection of an oversized adoption interval, and failed deletion/relabel at the end. Duplicate occurrence IDs remain distinct positions in the list: deleting a parent among three equal-ID nodes removes only one token. Uniqueness is not silently assumed, and insertion freshness is explicitly excluded by the manifest. Finite inductive trees rule out cycles and sharing structurally; the occurrence predicate expresses the separate ID condition.

For vacuity beyond edits, concrete positive proof counts satisfy `decideClosure`; excess proved counts block. A four-item scored pool with an interior tied minimum selects the first such item, and a failure after two zero-cost candidates still prevents a result. The closure properties concern supplied counts, and the pool properties concern a supplied pure evaluator. Evidence truthfulness, Java/Python/JavaScript refinement, semantic distance, parser behavior, and normalization correctness remain excluded. The lower-tier reviews correctly respect those boundaries; their absence of breaches is not used as proof.

Probe: `build/b01-astra-a/probe.lean`, SHA-256 `6657d96c683738eb6f40f7a9140dcf395a26f81ac579dadd1d039035ecfd61cd`. Final execution exited 0 with no diagnostics:

```sh
python3 scripts/lean_offline.py --cwd formal --lean-path build/lean-block-check-strict -- ../build/b01-astra-a/probe.lean --trust=0 -DwarningAsError=true -DgenInjectivity=false -j1
```

No internet, elan, installation, or frozen-source edit was performed. Existing compiled dependencies were used for these supplemental probes; this review does not replace the registered independent clean builds and theorem inventory checks.
