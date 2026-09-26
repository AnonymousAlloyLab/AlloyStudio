# Bundled exercise data

`catalogue.json` and `correct-pools.json` are tracked in this public repository.
A fresh clone includes the complete data needed to run the portal: no original
ACGN checkout, `classified-data/` directory, or IIS archive is required. From
the repository root, run `./scripts/run.sh`, or `./scripts/setup.sh` to validate
and build without starting the server. Local use requires Python 3.10+ and a
JDK 17+, with no Node runtime.

| File | Contents |
| --- | --- |
| `catalogue.json` | 181 exercises, natural-language descriptions, preserved Alloy environments, starters, oracle bodies, original selected sources, source hashes, and preservation records. |
| `correct-pools.json` | 181 pools containing 7,550 deduplicated corpus candidates and 181 explicit oracles, for 7,731 candidates total, with source witnesses and provenance. |

The import source is `ACGN/classified-data`. The catalogue records selection
from 66,080 candidate files. The pools inventory 23,694 correctly labelled source
files; 92 are excluded because their supporting environment or oracle does not
match the selected exercise. The remaining 23,602 sources yield 7,550 distinct
corpus candidates, 13,170 duplicate sources, and 2,882 sources represented by
the explicit oracle. Five pools contain only their oracle.

Each selected catalogue source and admitted candidate includes its original
source witness and SHA-256 binding. Excluded sources also carry witnesses.
Nonrepresentative duplicate sources retain inventory paths and hashes, rather
than separate source bodies. The validators check these embedded witnesses,
the preserved context, pool coverage, ordering, and counts without accessing
the original corpus. A `correct` label is inherited from the corpus; this is
provenance and context validation, not a new unrestricted semantic proof.

The browser receives a restricted exercise projection and redacted repair
feedback. It does not receive oracle bodies, candidate bodies, or the selected
target. Anyone reading or cloning this repository can inspect those solutions
in the bundled files. Browser redaction supports the learning interface and
does not promise secrecy for public source data.

Credentials have a separate boundary: `openai.local.json`, key files, and local
secrets are ignored by Git, excluded from release archives, and kept outside
public HTTP directories. Exercise data must also remain outside an IIS
`wwwroot` even though the repository publishes it; the server controls its
browser-facing projection.

If the bundled pair is missing or damaged, preserve intentional local data
edits and restore the matching files from the current Git revision:

```bash
git restore --source=HEAD -- exercises/catalogue.json exercises/correct-pools.json
./scripts/setup.sh
```

Original-corpus imports and trusted IIS archive restoration remain optional for
custom datasets or legacy checkouts. See [local setup](../docs/local-setup.md#restore-or-import-exercise-data).
