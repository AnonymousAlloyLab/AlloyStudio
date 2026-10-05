import IngressRouteModel

namespace AlloyStudio.Traffic
open AlloyStudio.IngressDecoder AlloyStudio.IngressWire

/-- Generated successful-route interpretation after complete class/call-graph
and wire-derived data-flow correspondence. No caller-supplied mutation flag,
header lengths, body lengths, text or parsed JSON tree enters the evaluator. -/
def dispatch (ipv6 : Octets → Bool) (primitives : BodyPrimitives) (input : Inbound) : Bool :=
  match actualRequest input.wire with
  | none => false
  | some request =>
    accepted Extracted.grammar ipv6 (Extracted.lineRules ++ Extracted.headerRules) request &&
    headersWithin input &&
    AlloyStudio.IngressDeadlines.Extracted.checkDeadline input.headerNow input.headerDeadline &&
    (if request.mutation then completedBody primitives input else true)

end AlloyStudio.Traffic
