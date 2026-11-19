var sig File {}
var sig Trash in File {}

pred inv4 {
  eventually some f: File | f in Trash implies always f in Trash
}

pred inv4c {
  eventually some f: File | always f in Trash
}

check correct { inv4 <=> inv4c } for 3
