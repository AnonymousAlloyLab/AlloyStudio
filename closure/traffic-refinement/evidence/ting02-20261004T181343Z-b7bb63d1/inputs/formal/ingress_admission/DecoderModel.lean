namespace AlloyStudio.IngressDecoder

abbrev Octets := List Nat

/- Closed regular languages. No regex-match result is an input to the theorem.
   The primitive runtime obligation is CPython fullmatch = this language. -/
inductive Regex where
  | empty | eps
  | atom (ranges : List (Nat × Nat))
  | seq (left right : Regex)
  | alt (left right : Regex)
  | star (child : Regex)

def nullable : Regex → Bool
  | .empty => false
  | .eps => true
  | .atom _ => false
  | .seq a b => nullable a && nullable b
  | .alt a b => nullable a || nullable b
  | .star _ => true

def derivative (byte : Nat) : Regex → Regex
  | .empty => .empty
  | .eps => .empty
  | .atom ranges => if ranges.any (fun pair => pair.1 ≤ byte && byte ≤ pair.2) then .eps else .empty
  | .seq a b => .alt (.seq (derivative byte a) b)
      (if nullable a then derivative byte b else .empty)
  | .alt a b => .alt (derivative byte a) (derivative byte b)
  | .star a => .seq (derivative byte a) (.star a)

def regexMatches (regex : Regex) (text : Octets) : Bool :=
  nullable (text.foldl (fun state byte => derivative byte state) regex)

structure Grammar where
  method : Regex
  target : Regex
  escaped : Regex
  headerName : Regex
  headerValue : Regex
  host : Regex
  contentType : Regex
  length : Regex
  versions : List Octets
  singletons : List Octets

def ows (value : Octets) : Octets :=
  ((value.dropWhile (fun c => c == 32 || c == 9)).reverse.dropWhile
    (fun c => c == 32 || c == 9)).reverse

def split (separator : Nat) : Octets → List Octets
  | [] => [[]]
  | character :: rest =>
    if character == separator then [] :: split separator rest else
    match split separator rest with
    | [] => [[character]]
    | word :: words => (character :: word) :: words

def lower (text : Octets) : Octets := text.map (fun character =>
  if 65 ≤ character && character ≤ 90 then character + 32 else character)

def hasPrefix : Octets → Octets → Bool
  | [], _ => true
  | _ :: _, [] => false
  | a :: rest, b :: tail => a == b && hasPrefix rest tail

def starts (text beginning : Octets) : Bool := hasPrefix beginning text

def endsCRLF (text : Octets) : Bool := hasPrefix [10, 13] text.reverse

def decimal (text : Octets) : Nat := text.foldl (fun number c => number * 10 + c - 48) 0

def lineParts (raw : Octets) : List Octets := split 32 (raw.reverse.drop 2).reverse

def elementAt {α : Type} : List α → Nat → α → α
  | [], _, fallback => fallback
  | head :: _, 0, _ => head
  | _ :: rest, n + 1, fallback => elementAt rest n fallback

def lineMethod (raw : Octets) : Octets := elementAt (lineParts raw) 0 []
def lineTarget (raw : Octets) : Octets := elementAt (lineParts raw) 1 []
def lineVersion (raw : Octets) : Octets := elementAt (lineParts raw) 2 []

def values (pairs : List (Octets × Octets)) (name : Octets) : List Octets :=
  (pairs.filter (fun pair => lower pair.1 == name)).map (fun pair => ows pair.2)

def first (pairs : List (Octets × Octets)) (name fallback : Octets) : Octets :=
  (values pairs name).headD fallback

structure Input where
  rawLine : Octets
  pairs : List (Octets × Octets)
  mutation : Bool

/- IPv6Address is the sole host recognizer primitive; regex/port checks are here. -/
def hostValid (grammar : Grammar) (ipv6 : Octets → Bool) (host : Octets) : Bool :=
  if !regexMatches grammar.host host then false else
  let bracketed := starts host [91]
  let literal := (split 93 (host.drop 1)).headD []
  let suffix := if bracketed then elementAt (split 93 host) 1 []
    else if (split 58 host).length == 2 then [58] ++ elementAt (split 58 host) 1 [] else []
  (!bracketed || ipv6 literal) && (suffix.isEmpty || decimal (suffix.drop 1) ≤ 65535)

inductive Rule where
  | crlf | threeParts | methodToken | version | targetGrammar | originOnly | noEscapedControls
  | headerVersion | headerNames | headerValues | singletons | hostRequired | hostGrammar
  | noTransferEncoding | identityEncoding | noExpect | contentTypeRequired | contentTypeGrammar
  | lengthGrammar | bodylessRead
  deriving DecidableEq

-- ASCII constants use octet lists to avoid adding String library proof axioms.
def http11 : Octets := [72, 84, 84, 80, 47, 49, 46, 49]
def hostName : Octets := [104, 111, 115, 116]
def transferName : Octets := [116, 114, 97, 110, 115, 102, 101, 114, 45, 101, 110, 99, 111, 100, 105, 110, 103]
def encodingName : Octets := [99, 111, 110, 116, 101, 110, 116, 45, 101, 110, 99, 111, 100, 105, 110, 103]
def identity : Octets := [105, 100, 101, 110, 116, 105, 116, 121]
def expectName : Octets := [101, 120, 112, 101, 99, 116]
def typeName : Octets := [99, 111, 110, 116, 101, 110, 116, 45, 116, 121, 112, 101]
def lengthName : Octets := [99, 111, 110, 116, 101, 110, 116, 45, 108, 101, 110, 103, 116, 104]

def holds (grammar : Grammar) (ipv6 : Octets → Bool) (input : Input) : Rule → Bool
  | .crlf => endsCRLF input.rawLine && input.rawLine.all (fun c => c < 128)
  | .threeParts => (lineParts input.rawLine).length == 3
  | .methodToken => regexMatches grammar.method (lineMethod input.rawLine)
  | .version => grammar.versions.contains (lineVersion input.rawLine)
  | .targetGrammar => regexMatches grammar.target (lineTarget input.rawLine)
  | .originOnly => !starts (lineTarget input.rawLine) [47, 47]
  | .noEscapedControls => !regexMatches grammar.escaped (lower (lineTarget input.rawLine))
  | .headerVersion => grammar.versions.contains (lineVersion input.rawLine)
  | .headerNames => input.pairs.all (fun pair => regexMatches grammar.headerName pair.1)
  | .headerValues => input.pairs.all (fun pair => regexMatches grammar.headerValue pair.2)
  | .singletons => grammar.singletons.all (fun name => (values input.pairs name).length ≤ 1)
  | .hostRequired => lineVersion input.rawLine != http11 || (values input.pairs hostName).length == 1
  | .hostGrammar => (values input.pairs hostName).isEmpty || hostValid grammar ipv6 (first input.pairs hostName [])
  | .noTransferEncoding => (values input.pairs transferName).isEmpty
  | .identityEncoding => lower (first input.pairs encodingName identity) == identity
  | .noExpect => (values input.pairs expectName).isEmpty
  | .contentTypeRequired => !input.mutation || (values input.pairs typeName).length == 1
  | .contentTypeGrammar => (values input.pairs typeName).isEmpty ||
      regexMatches grammar.contentType (lower (first input.pairs typeName []))
  | .lengthGrammar => regexMatches grammar.length (first input.pairs lengthName [48])
  | .bodylessRead => input.mutation || decimal (first input.pairs lengthName [48]) == 0

def accepted (grammar : Grammar) (ipv6 : Octets → Bool) (rules : List Rule) (input : Input) : Bool :=
  rules.all (holds grammar ipv6 input)

/- A successful JSON syntax/UTF8 primitive produces an ordered tree. Every
object's duplicates remain represented. A scalar's serialization bit records
only the named finite-number/valid-Unicode encoding primitive, not validation
of the request, its depth, object keys, root type or framing. -/
inductive JsonTree where
  | scalar (serializable : Bool)
  | array (items : List JsonTree)
  | object (items : List (Octets × JsonTree))

def uniqueKeys : List (Octets × JsonTree) → Bool
  | [] => true
  | (key, _) :: rest => !(rest.any (fun pair => pair.1 == key)) && uniqueKeys rest

def unicodeKey (key : Octets) : Bool :=
  key.all (fun codepoint => codepoint ≤ 1114111 && !(55296 ≤ codepoint && codepoint ≤ 57343))

mutual
  def treePolicies : JsonTree → Bool
    | .scalar serializable => serializable
    | .array items => arrayPolicies items
    | .object pairs => uniqueKeys pairs && objectPolicies pairs
  def arrayPolicies : List JsonTree → Bool
    | [] => true
    | item :: rest => treePolicies item && arrayPolicies rest
  def objectPolicies : List (Octets × JsonTree) → Bool
    | [] => true
    | (key, item) :: rest => unicodeKey key && treePolicies item && objectPolicies rest
end

def objectRoot : JsonTree → Bool
  | .object _ => true
  | .scalar _ => false
  | .array _ => false

structure Scan where
  depth : Int := 0
  quoted : Bool := false
  escaped : Bool := false
  bounded : Bool := true

def scanStep (limit : Int) (state : Scan) (character : Nat) : Scan :=
  if state.quoted then
    if state.escaped then {state with escaped := false}
    else if character == 92 then {state with escaped := true}
    else if character == 34 then {state with quoted := false}
    else state
  else if character == 34 then {state with quoted := true}
  else if character == 91 || character == 123 then
    {state with depth := state.depth + 1, bounded := state.bounded && state.depth + 1 ≤ limit}
  else if character == 93 || character == 125 then {state with depth := state.depth - 1}
  else state

def depthBounded (text : Octets) (limit : Int) : Bool :=
  (text.foldl (scanStep limit) {}).bounded

def jsonAccepted (text : Octets) (limit : Int) (tree : JsonTree) : Bool :=
  depthBounded text limit && treePolicies tree && objectRoot tree

end AlloyStudio.IngressDecoder
