import Decoder

namespace AlloyStudio.IngressWire
open AlloyStudio.IngressDecoder

/-- Exact request bytes consumed by the parser. rawHeaders includes its actual
terminal CRLF blank line; no delimiter or length is supplied by the model. Extra
TCP bytes after the consumed body are outside this one-request connection. -/
structure Wire where
  rawLine : Octets
  rawHeaders : List Octets
  rawBody : Octets

def crlf : Octets := [13, 10]
def postMethod : Octets := [80, 79, 83, 84]

def partitionColon : Octets → Option (Octets × Octets)
  | [] => none
  | character :: rest =>
    if character == 58 then some ([], rest) else
    match partitionColon rest with
    | none => none
    | some (name, value) => some (character :: name, value)

/-- Mirrors the raw reader's CRLF/token/control/folding checks. Once those pass,
stdlib header parsing only removes leading OWS; strict_request_headers removes
both ends. Returning the OWS-normalized value therefore preserves every observed
strict-decoder lookup, including multiplicity and comma/charset ambiguity. -/
def headerPair (line : Octets) : Option (Octets × Octets) :=
  if !endsCRLF line || starts line [32] || starts line [9] then none else
  match partitionColon (line.reverse.drop 2).reverse with
  | none => none
  | some (name, value) =>
    if regexMatches Extracted.grammar.headerName name &&
       regexMatches Extracted.grammar.headerValue value then some (name, ows value)
    else none

def headerPairs : List Octets → Option (List (Octets × Octets))
  | [] => none
  | line :: rest =>
    if line == crlf then
      if rest.isEmpty then some [] else none
    else match headerPair line with
      | none => none
      | some pair => match headerPairs rest with
        | none => none
        | some pairs => some (pair :: pairs)

/-- In particular, POST cannot borrow the nonmutation request policy. -/
def actualRequest (wire : Wire) : Option Input :=
  match headerPairs wire.rawHeaders with
  | none => none
  | some pairs => some {
      rawLine := wire.rawLine
      pairs := pairs
      mutation := lineMethod wire.rawLine == postMethod }

def headerLengths (wire : Wire) : List Nat :=
  wire.rawLine.length :: wire.rawHeaders.map List.length

def declaredLength (wire : Wire) : Nat :=
  match actualRequest wire with
  | none => 0
  | some request => decimal (first request.pairs lengthName [48])

def receivedLength (wire : Wire) : Nat := wire.rawBody.length

/-- These are the separately named, standard-runtime primitives in the TCB.
utf8 returns decoded Unicode codepoints or fails. json consumes those same
codepoints, preserving ordered object pairs, and records the standard finite
number/Unicode serialization result at scalar leaves. Neither primitive decides
request framing, method policy, byte budgets, depth policy or root-object policy. -/
structure BodyPrimitives where
  utf8 : Octets → Option Octets
  json : Octets → Option JsonTree

structure DecodedBody where
  text : Octets
  tree : JsonTree

def decodedBody (primitives : BodyPrimitives) (wire : Wire) : Option DecodedBody :=
  match primitives.utf8 wire.rawBody with
  | none => none
  | some text => match primitives.json text with
    | none => none
    | some tree => some {text := text, tree := tree}

def bodyAccepted (primitives : BodyPrimitives) (wire : Wire) (depthLimit : Int) : Bool :=
  match decodedBody primitives wire with
  | none => false
  | some body => jsonAccepted body.text depthLimit body.tree

theorem actual_request_binding (wire : Wire) (request : Input)
    (parsed : actualRequest wire = some request) :
    request.rawLine = wire.rawLine ∧
    request.mutation = (lineMethod wire.rawLine == postMethod) ∧
    headerPairs wire.rawHeaders = some request.pairs := by
  unfold actualRequest at parsed
  cases headers : headerPairs wire.rawHeaders with
  | none => rw [headers] at parsed; cases parsed
  | some pairs =>
    rw [headers] at parsed
    cases parsed
    exact ⟨rfl, rfl, rfl⟩

theorem actual_post_requires_mutation (wire : Wire) (request : Input)
    (method : lineMethod wire.rawLine = postMethod)
    (parsed : actualRequest wire = some request) : request.mutation = true := by
  have bound := (actual_request_binding wire request parsed).2.1
  rw [method] at bound
  exact bound

theorem post_false_mutation_impossible (wire : Wire) (request : Input)
    (method : lineMethod wire.rawLine = postMethod)
    (parsed : actualRequest wire = some request)
    (flipped : request.mutation = false) : False := by
  have actual := actual_post_requires_mutation wire request method parsed
  rw [flipped] at actual
  cases actual

theorem header_lengths_are_received_bytes (wire : Wire) :
    headerLengths wire = wire.rawLine.length :: wire.rawHeaders.map List.length := rfl

theorem body_length_is_received_bytes (wire : Wire) : receivedLength wire = wire.rawBody.length := rfl

theorem declared_length_is_parsed_header (wire : Wire) (request : Input)
    (parsed : actualRequest wire = some request) :
    declaredLength wire = decimal (first request.pairs lengthName [48]) := by
  unfold declaredLength
  rw [parsed]

theorem decoded_body_binding (primitives : BodyPrimitives) (wire : Wire) (body : DecodedBody)
    (parsed : decodedBody primitives wire = some body) :
    primitives.utf8 wire.rawBody = some body.text ∧ primitives.json body.text = some body.tree := by
  unfold decodedBody at parsed
  cases decoded : primitives.utf8 wire.rawBody with
  | none => rw [decoded] at parsed; cases parsed
  | some text =>
    rw [decoded] at parsed
    change (match primitives.json text with
      | none => none
      | some tree => some {text := text, tree := tree}) = some body at parsed
    cases json : primitives.json text with
    | none => rw [json] at parsed; cases parsed
    | some tree =>
      rw [json] at parsed
      cases parsed
      exact ⟨rfl, json⟩

theorem accepted_body_has_bound_parse (primitives : BodyPrimitives) (wire : Wire) (limit : Int)
    (accepted : bodyAccepted primitives wire limit = true) :
    ∃ body, decodedBody primitives wire = some body ∧
      primitives.utf8 wire.rawBody = some body.text ∧ primitives.json body.text = some body.tree ∧
      depthBounded body.text limit = true ∧ treePolicies body.tree = true ∧ objectRoot body.tree = true := by
  unfold bodyAccepted at accepted
  cases decoded : decodedBody primitives wire with
  | none => rw [decoded] at accepted; cases accepted
  | some body =>
    rw [decoded] at accepted
    have bound := decoded_body_binding primitives wire body decoded
    have policy := json_acceptance_requires_all_policies body.text limit body.tree accepted
    exact ⟨body, rfl, bound.1, bound.2, policy.1, policy.2.1, policy.2.2⟩

/- Concrete versions of the reviewer's counterexample and terminator attacks. -/
def lunaPost : Wire := {
  rawLine := [80, 79, 83, 84, 32, 47, 97, 112, 105, 47, 102, 101, 101, 100, 98, 97, 99, 107,
    32, 72, 84, 84, 80, 47, 49, 46, 49, 13, 10]
  rawHeaders := [[72, 111, 115, 116, 58, 32, 108, 111, 99, 97, 108, 104, 111, 115, 116, 13, 10], [13, 10]]
  rawBody := []
}

theorem luna_post_is_a_mutation :
    (actualRequest lunaPost).map (fun request => request.mutation) = some true := by decide

theorem luna_header_lengths_cannot_be_empty :
    headerLengths lunaPost = [29, 17, 2] := rfl

theorem missing_terminator_rejected :
    headerPairs [[72, 111, 115, 116, 58, 32, 120, 13, 10]] = none := by decide

theorem early_terminator_rejected : headerPairs [[13, 10], [13, 10]] = none := rfl

theorem header_without_crlf_rejected : headerPair [72, 111, 115, 116, 58, 32, 120] = none := rfl

theorem folded_header_rejected : headerPair [32, 120, 58, 32, 120, 13, 10] = none := rfl

end AlloyStudio.IngressWire
