import IngressRouteModel

namespace AlloyStudio.Traffic
open AlloyStudio.IngressDecoder

/-- Rejection returns false before business dispatch. This evaluator executes
the registered validators on their raw representation, not a validity premise. -/
def dispatch (ipv6 : Octets → Bool) (input : Inbound) : Bool :=
  accepted Extracted.grammar ipv6 (Extracted.lineRules ++ Extracted.headerRules) input.request &&
  headersWithin input &&
  AlloyStudio.IngressDeadlines.Extracted.checkDeadline input.headerNow input.headerDeadline &&
  (if input.request.mutation then completedBody input else true)


end AlloyStudio.Traffic
