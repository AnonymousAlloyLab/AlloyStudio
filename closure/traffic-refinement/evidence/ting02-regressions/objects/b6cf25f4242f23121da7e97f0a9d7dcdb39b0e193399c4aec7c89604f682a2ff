sig S {}

pred inv1 {
  all y: S | let x = y | some y: S | x != y
}

pred inv1c {
  all y: S | some y: S | y != y
}

check correct { inv1 <=> inv1c } for 3
