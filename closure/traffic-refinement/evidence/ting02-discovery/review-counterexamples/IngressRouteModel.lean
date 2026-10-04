import AdmissionSpec
import Decoder
import IngressDeadlines.Spec

/- Composition at the successful ingress observation cut. Header lengths denote
actual bounded reader outputs; body length denotes actual accumulated recv bytes.
The closed call-graph bridge fixes those representations, exact-length loop,
exception exits and parser-to-handler dispatch. No decoded-valid input Boolean
or initial-state invariant is supplied as a premise. -/
namespace AlloyStudio.Traffic

open AlloyStudio.IngressDecoder

structure Inbound where
  request : Input
  headerLengths : List Nat
  lineLimit : Nat
  headerLimit : Nat
  headerCountLimit : Nat
  headerNow : Int
  headerDeadline : Int
  declaredLength : Nat
  receivedLength : Nat
  bodyLimit : Nat
  bodyText : Octets
  bodyTree : JsonTree
  depthLimit : Int
  bodyNow : Int
  bodyDeadline : Int

def byteCount : List Nat → Nat
  | [] => 0
  | size :: rest => size + byteCount rest

def headersWithin (input : Inbound) : Bool :=
  input.headerLengths.all (fun size => decide (size ≤ input.lineLimit)) &&
  decide (byteCount input.headerLengths ≤ input.headerLimit) &&
  decide (input.headerLengths.length ≤ input.headerCountLimit + 2)

def bodyWithin (input : Inbound) : Bool :=
  decide (0 < input.declaredLength) && decide (input.declaredLength ≤ input.bodyLimit) &&
  decide (input.receivedLength = input.declaredLength)

def completedBody (input : Inbound) : Bool :=
  bodyWithin input && jsonAccepted input.bodyText input.depthLimit input.bodyTree &&
  AlloyStudio.IngressDeadlines.Extracted.checkDeadline input.bodyNow input.bodyDeadline


def Validated (ipv6 : Octets → Bool) (input : Inbound) : Prop :=
  StrictRequest ipv6 input.request ∧
  headersWithin input = true ∧
  AlloyStudio.IngressDeadlines.Model.Before input.headerNow input.headerDeadline ∧
  (input.request.mutation = true →
    bodyWithin input = true ∧
    depthBounded input.bodyText input.depthLimit = true ∧
    treePolicies input.bodyTree = true ∧ objectRoot input.bodyTree = true ∧
    AlloyStudio.IngressDeadlines.Model.Before input.bodyNow input.bodyDeadline)

end AlloyStudio.Traffic
