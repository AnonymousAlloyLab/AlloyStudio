# Updated graphs.inv7 AST infographic

This single-column figure uses ACGN commit `8c9bd4b922ecee99d496be2515fa78cb09f3e12b` and the rebased AlloyStudio adapter. NOOP and Body wrappers are erased before counting, numbering and distance computation. This is an actual metric update, rather than a display-only omission.

The same published pair is retained: student `all n:Node | Node in n.^adj+n.^~adj`; selected correct reference `all n:Node | Node in n.(^adj + iden)`. This reference is not claimed to be AST-nearest. The complete ASTs now have 17 and 13 nodes. The full and differing-subtree unit-cost distances are 8. The previous wrapper-counting result of 10 and its original evidence remain in the sibling `zhang-shasha-graphs-inv7` directory.

The production replay deletes S7, S8, S9, S10 and S12; relabels S14 (join) to T10 (union) and S15 (union) to T11 (join); inserts T9 (iden). It retains S13 (closure) as T8. Thus 5 deletions+2 relabels+1 insertion=8. All 10 student RHS nodes and all 6 reference RHS nodes are drawn; only unchanged surrounding context is omitted. Deletion promotes children, so intermediate ordered trees need not be well-typed Alloy code. Another minimum-cost alignment may exist.

Use `figure.tex` with the PDF in `../pdf/graphs-inv7-zhang-shasha-acgn-20261109.pdf`. The Graphviz source can be rendered with `neato -n2 -Tpdf figure.dot -o figure.pdf`.
