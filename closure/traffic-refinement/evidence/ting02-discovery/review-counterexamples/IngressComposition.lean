import IngressRouteExtracted

namespace AlloyStudio.Traffic
open AlloyStudio.IngressDecoder

theorem bool_and_parts (left right : Bool) (h : (left && right) = true) :
    left = true ∧ right = true := by
  cases left with
  | false => cases h
  | true => exact ⟨rfl, h⟩

theorem completed_body_validated (input : Inbound) (h : completedBody input = true) :
    bodyWithin input = true ∧ depthBounded input.bodyText input.depthLimit = true ∧
    treePolicies input.bodyTree = true ∧ objectRoot input.bodyTree = true ∧
    AlloyStudio.IngressDeadlines.Model.Before input.bodyNow input.bodyDeadline := by
  have outer := bool_and_parts _ _ h
  have inner := bool_and_parts _ _ outer.1
  have json := json_acceptance_requires_all_policies _ _ _ inner.2
  exact ⟨inner.1, json.1, json.2.1, json.2.2,
    AlloyStudio.IngressDeadlines.Spec.check_deadline_sound _ _ outer.2⟩

/-- Original planned parent theorem: every accepted concrete ingress recipe
satisfies the independent decoder and sampled byte/deadline contracts. -/
theorem admitted_is_validated (ipv6 : Octets → Bool) (input : Inbound)
    (h : dispatch ipv6 input = true) : Validated ipv6 input := by
  have outer := bool_and_parts _ _ h
  have middle := bool_and_parts _ _ outer.1
  have inner := bool_and_parts _ _ middle.1
  refine ⟨decoder_sound ipv6 input.request inner.1, inner.2,
    AlloyStudio.IngressDeadlines.Spec.check_deadline_sound _ _ middle.2, ?_⟩
  intro mutation
  have body := outer.2
  rw [mutation] at body
  exact completed_body_validated input body

/-- Lane ownership validity is derived from empty startup for every transition
history. It is not an extra assumption in the composed conclusion. -/
theorem admitted_with_reachable_capacity (ipv6 : Octets → Bool) (input : Inbound)
    (limit : Nat) (events : List AlloyStudio.IngressAdmission.Spec.Event)
    (h : dispatch ipv6 input = true) :
    Validated ipv6 input ∧ AlloyStudio.IngressAdmission.Valid limit
      (AlloyStudio.IngressAdmission.Spec.run limit events AlloyStudio.IngressAdmission.initial) :=
  ⟨admitted_is_validated ipv6 input h,
   AlloyStudio.IngressAdmission.Spec.all_reachable_count_and_capacity limit events⟩

theorem body_exact_and_bounded (input : Inbound) (h : bodyWithin input = true) :
    0 < input.declaredLength ∧ input.declaredLength ≤ input.bodyLimit ∧
    input.receivedLength = input.declaredLength := by
  have outer := bool_and_parts _ _ h
  have inner := bool_and_parts _ _ outer.1
  exact ⟨of_decide_eq_true inner.1, of_decide_eq_true inner.2, of_decide_eq_true outer.2⟩

end AlloyStudio.Traffic
