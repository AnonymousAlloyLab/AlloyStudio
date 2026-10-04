sig S { r: set S }
one sig A, B extends S {}

pred inv2 {
  A->B in { x, y: S | y in x.r }
}

pred inv2c {
  A->B in { x, y: S | x in y.r }
}

check correct { inv2 <=> inv2c } for 3
