# Reading concrete Alloy instances

After checking a draft, open a behavioral category and select **Example 1**,
**Example 2**, or **Example 3**. Each available example has its own graphical
view. A category with no instance within the checked bounds does not get an
invented drawing.

- **Both accept:** the oracle and your predicate accept the example.
- **Undercoverage:** the oracle accepts it, but your predicate excludes it.
- **Overcoverage:** your predicate accepts it, but the oracle excludes it.
- **Neither accepts:** both reject it.

Every example satisfies the model facts. Its category tells you how the two
predicates evaluate the complete instance; it does not identify a particular
arrow as an error. The oracle predicate remains hidden.

## Objects and connections

An object, called an *atom* in Alloy, is one member of a signature. Names such
as `Node$0` and `Node$1` distinguish two concrete objects. Their placement in the
drawing is chosen for readability and has no meaning in the Alloy model.

Each object card shows its memberships when they fit, or a type and a count of
additional memberships. Select the card to read every membership. An atom in both a parent signature and a
subset still appears only once. A compact name such as `Node 0` corresponds to
the exact identity `Node$0`, which remains in its details and the tables.
The picture does not infer a
signature hierarchy that is absent from the returned data. Integers and hidden
string values can also appear as connection endpoints even when they have no
named-signature row. A name such as `String$0` identifies an anonymized value;
the original string contents stay hidden.

For a binary relation, follow a labeled arrow from its first object to its
second. An arrow returning to the same object represents a self-connection.
Opposite directions and multiple relations are kept distinct.

A relation with more than two columns needs more than a pairwise arrow. The
diagram uses a tuple marker and numbered connections: **1** is the first column,
**2** the second, and so on. These numbers preserve order even when the same
object fills several positions. The marker is a drawing aid, not an additional
Alloy object. Unary relations use the same notation with one numbered position.

The layout follows connections from top to bottom, so chains, branches, and
shared downstream objects form a hierarchy. Cyclic groups remain together;
their arrows can return to the same level. Disconnected objects remain visible.
For tuples with several columns, the first column and tuple marker guide the
layout; numbered spokes still show the actual ordered tuple. This arrangement
does not declare parenthood or change the model's relationships.

Object cards and connection labels have separate, measured space. Connections
keep readable relation names rather than requiring a lookup of numbered codes.
Very long names are shortened on the drawing, with their full names available
on hover, selection, and in the relation list and exact tables. Distinct relation
names must remain distinguishable. Connections follow orthogonal routes around
cards and labels, with separate ports and a preference for simpler paths. Two
connection lines can still cross in a complex graph; a crossing does not
represent an object or an extra relationship.

Use the relation selector to focus on a single relation. Select an object or
connection with the mouse, or focus it with the keyboard and press Enter or
Space, to read its concrete values and emphasize its nearby connections.
**Show all connections** clears this emphasis. It does not change the relation
selector. **How to read this diagram** opens the notation guide when needed.
The exact-data disclosure below the picture contains the returned atom and
relation tables.

## Temporal examples and large instances

For temporal models, use **Trace state** to change the displayed state. Both the
diagram and tables update together. The existing loop note explains which
state follows the last state; the graph itself shows only the selected state.
Examples and states are not merged into a single diagram.

Large inputs use an explicit diagram limit to keep interaction responsive.
The display reports omitted objects or connections; absence from a limited
picture does not establish absence from the instance. Select an individual
relation or consult the exact tables for the returned data. A separate backend
truncation notice means some instance data was already omitted before reaching
the browser. The picture cannot recover those omitted values.

On a narrow screen, the diagram starts centered on a complete object. Scroll
within it to inspect the remaining objects at normal text size, or choose
**Fit width** for an overview. **100%** restores normal size; the zoom buttons
adjust the view between 25% and 150%. Large diagrams also scroll vertically
inside the picture. The page itself remains within the screen.

## Implementation boundary

`web/instance-graph.js` renders a deterministic SVG from the existing validated
behavioral response. `web/app.js` creates it whenever the selected example or
trace state changes. It uses browser DOM text APIs rather than interpreting
atom or relation labels as HTML or SVG markup. The feature makes no image-model
or external graph-service requests and adds no runtime package dependency.

The renderer does not change sampling, the three-example limit, category
classification, the behavioral score, or any oracle/correct-pool policy. Luna's
existing explanation still accompanies each example; it does not supply the
diagram's structure. IIS packages include the module and version its import
along with the application's other public assets.

## Layout regression checks

Browser tests include six recorded public solver instances from
`productionLineNew · inv3`, at desktop and mobile widths. The checks measure the
rendered SVG rather than reusing the renderer's coordinates: text must fit its
card and canvas, text and object boxes must not overlap unrelated boxes, and
connections must avoid unrelated objects and text. Additional cases cover long
names, parallel and reverse relations, self-connections, repeated higher-arity
columns, filtering, and the display limit. The public fixture contains only
concrete instance data and the learner's draft, with no oracle predicate.

`tests/instance-hierarchy.mjs` additionally checks chains, trees, shared-child
DAGs, cycles, disconnected components, visible relation names, and exact tuple
and membership retention. It can replay the retained public 181-invariant
instance audit at desktop and mobile widths without starting Alloy or calling
an AI provider. Each report records its input and renderer hashes; it is a
finite rendering check, not a claim about every possible Alloy instance.

The 2026-10-07 validation passed **36 focused checks** and **2,878 desktop/mobile
checks** over all **181 invariants**, replaying **1,342 retained instances** with
**1,439 states**. All returned atoms, memberships, and ordered tuples were
preserved in the real instances; the measured checks found no overlapping
objects or text, clipped canvas contents, or routes through unrelated objects
and labels. Initial mobile views include a complete object in the uppermost
visible row. Focus, filtering, fitting, zoom, and reset also retain the instance
data. This replay used no solver, provider, or external network requests.

The complete **88-scenario browser suite** also passed, including editor and
guidance regressions and the IIS asset/import checks. A fresh timestamped IIS
archive was built and its diagram module and stylesheet matched the checkout.
These are local browser and package checks, not a new native IIS deployment.

Run the focused checks with:

```sh
node tests/instance-hierarchy.mjs --output=build/instance-hierarchy/check
```

If the retained public audit responses are present locally, add
`--cached-responses=build/instance-audit-v003/responses` to replay every recorded
state. The final local receipt is
`build/instance-hierarchy/tests/final-bound-cached/report.json`; it binds the
renderer, stylesheet, fixture, and independent checks to their SHA-256 hashes.
Earlier attempt receipts remain available alongside it.
