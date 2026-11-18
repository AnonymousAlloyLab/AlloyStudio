sig File {}
sig Trash in File {}

pred inv3 {
  all Trash: File | File in Trash
}

pred inv3c {
  File in Trash
}

check correct { inv3 <=> inv3c } for 3
