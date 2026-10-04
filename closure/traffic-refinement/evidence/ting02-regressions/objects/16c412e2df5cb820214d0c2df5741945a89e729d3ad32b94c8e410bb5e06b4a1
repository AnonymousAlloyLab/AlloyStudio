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

The diagram lists each object's signature memberships. An atom in both a parent
signature and a subset still appears only once. The picture does not infer a
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

Object cards and connection labels have separate, measured space. Long names
are shortened to fit their cards, and memberships wrap onto separate lines;
select the card to read the full name and memberships. Small sets of related
types are arranged in columns when that reduces clutter. Connections follow
orthogonal routes around cards and labels, with separate ports and a preference
for unused tracks. Two connection lines can still cross in a complex graph;
a crossing does not represent an object or an extra relationship.

Use the relation selector to focus on a single relation. Select an object or
connection with the mouse, or focus it with the keyboard and press Enter or
Space, to read its concrete values. The exact-data disclosure below the picture
contains the returned atom and relation tables.

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

On a narrow screen, the diagram starts centered so a small instance is visible
immediately. Scroll within it to inspect the remaining objects without shrinking
labels to unreadable sizes. Large diagrams also scroll vertically inside the
picture. The page itself remains within the screen.

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
