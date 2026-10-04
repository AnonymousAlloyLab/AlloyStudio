import AdmissionSpec
import IngressWire
import IngressDeadlines.Spec

/- All syntactic data and byte counts come from the exact consumed wire. The
only observations supplied separately are configured budgets and sampled clocks.
UTF8/JSON outcomes come from the explicitly named runtime primitives. -/
namespace AlloyStudio.Traffic
open AlloyStudio.IngressDecoder AlloyStudio.IngressWire

structure Inbound where
  wire : Wire
  lineLimit : Nat
  headerLimit : Nat
  headerCountLimit : Nat
  headerNow : Int
  headerDeadline : Int
  bodyLimit : Nat
  depthLimit : Int
  bodyNow : Int
  bodyDeadline : Int

def byteCount : List Nat → Nat
  | [] => 0
  | size :: rest => size + byteCount rest

def headersWithin (input : Inbound) : Bool :=
  (headerLengths input.wire).all (fun size => decide (size ≤ input.lineLimit)) &&
  decide (byteCount (headerLengths input.wire) ≤ input.headerLimit) &&
  decide ((headerLengths input.wire).length ≤ input.headerCountLimit + 2)

def bodyWithin (input : Inbound) : Bool :=
  decide (0 < declaredLength input.wire) && decide (declaredLength input.wire ≤ input.bodyLimit) &&
  decide (receivedLength input.wire = declaredLength input.wire)

def completedBody (primitives : BodyPrimitives) (input : Inbound) : Bool :=
  bodyWithin input && bodyAccepted primitives input.wire input.depthLimit &&
  AlloyStudio.IngressDeadlines.Extracted.checkDeadline input.bodyNow input.bodyDeadline

def BodyValidated (primitives : BodyPrimitives) (input : Inbound) : Prop :=
  bodyWithin input = true ∧
  (∃ body, decodedBody primitives input.wire = some body ∧
    primitives.utf8 input.wire.rawBody = some body.text ∧
    primitives.json body.text = some body.tree ∧
    depthBounded body.text input.depthLimit = true ∧ treePolicies body.tree = true ∧
    objectRoot body.tree = true) ∧
  AlloyStudio.IngressDeadlines.Model.Before input.bodyNow input.bodyDeadline

def Validated (ipv6 : Octets → Bool) (primitives : BodyPrimitives) (input : Inbound) : Prop :=
  ∃ request, actualRequest input.wire = some request ∧
    StrictRequest ipv6 request ∧
    request.mutation = (lineMethod input.wire.rawLine == postMethod) ∧
    headersWithin input = true ∧
    AlloyStudio.IngressDeadlines.Model.Before input.headerNow input.headerDeadline ∧
    (request.mutation = true → BodyValidated primitives input)

end AlloyStudio.Traffic
