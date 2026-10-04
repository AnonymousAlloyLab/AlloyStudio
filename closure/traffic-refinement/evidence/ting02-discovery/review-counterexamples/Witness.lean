import IngressComposition
open AlloyStudio.Traffic AlloyStudio.IngressDecoder

def witness : Inbound := {
  request := {rawLine := [80, 79, 83, 84, 32, 47, 97, 112, 105, 47, 102, 101, 101, 100, 98, 97, 99, 107, 32, 72, 84, 84, 80, 47, 49, 46, 49, 13, 10], pairs := [([72, 111, 115, 116], [108, 111, 99, 97, 108, 104, 111, 115, 116])], mutation := false}
  headerLengths := []
  lineLimit := 8192
  headerLimit := 16384
  headerCountLimit := 100
  headerNow := 0
  headerDeadline := 5
  declaredLength := 0
  receivedLength := 0
  bodyLimit := 16384
  bodyText := []
  bodyTree := .object []
  depthLimit := 32
  bodyNow := 6
  bodyDeadline := 5
}

theorem post_without_json_admitted : dispatch (fun _ => false) witness = true := by decide

theorem body_guard_would_reject : completedBody witness = false := by decide
