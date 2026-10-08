# Concrete instance labels and relation selection

This change makes the existing hierarchical instance diagram easier to read in
the style of the Alloy visualizer: named objects have visible types, connections
have relation names, and a value can be explored through its actual relations.
The hierarchy, supplied atom inventory, tuple inventory, direction and column
order remain unchanged.

## Contract

1. An atom whose complete supplied name is a signed decimal number must never
   have a number-only primary box label. Its primary label is the supplied first
   signature and the complete number, or `Value` and the complete number if the
   public state does not supply signature membership. The renderer must not
   infer `Int` membership from a numeric spelling. Long labels may be shortened
   to fit the existing box; exact names remain in accessible and selected
   descriptions.
2. Object selection highlights exactly the rendered tuples containing that
   object and their endpoints. Its details identify the actual relation names
   and provide a button for each distinct adjacent rendered relation. Selecting
   one of those buttons highlights precisely that relation's rendered tuples
   and endpoints. Different relations with equal labels remain distinguished by
   their supplied indexes, never by a name-only lookup.
3. Relation-key buttons support mouse and keyboard selection. Selection provides
   the complete name, supplied tuple count, and rendered tuple count. A relation
   with no visible tuples reports that fact without inventing an edge. Selecting
   an off-filter relation may show the supplied count but must not claim its
   tuples are visible or change the filter silently.
4. `Show all connections` clears atom, tuple, relation-button and legend focus
   state. Changing the relation filter clears stale selection. Zoom, filtering,
   SCC hierarchy, ordered high-arity spokes, repeated columns, isolates,
   temporal state views and the existing 40-node/48-tuple default caps retain
   their behavior. Exact data tables elsewhere in the portal remain unchanged.
5. Uploaded names are text, including relation-button text. There is no HTML
   interpretation, new network request, inference, solver request or provider
   request in this rendering path. Events apply only to their own instance.
6. The existing collision-free geometry obligations remain: object cards and
   text fit their reserved regions; routes avoid unrelated cards and labels;
   small viewports contain a complete initial object and no page overflow.

## Validation

An isolated Playwright test uses an ephemeral loopback port and a fixed public
asset allowlist. It checks numeric values, supplied numeric atom memberships,
duplicate relation names, empty and filtered-out relations, exact keyboard and
click highlighting, reset, text-only handling and mobile/desktop geometry.
The existing hierarchical instance harness separately exercises realistic
production-line states, cycles, repeated tuple columns, long labels, bounded
instances and all retained state data without invoking Alloy or a provider.
