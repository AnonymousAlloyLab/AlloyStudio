module permutation

sig S {}

one sig R {
    f: S -> S
}

pred inv[] {
    all x, y: S | x -> y in R.f
}

pred invC[] {
    all x, y: S | y -> x in R.f
}

pred overconstrained[] {
    invC[] and not inv[]
}

pred underconstrained[] {
    not invC[] and inv[]
}

pred both[] {
    invC[] and inv[]
}

run overconstrained
run underconstrained
run both
run invC
run { not invC }
