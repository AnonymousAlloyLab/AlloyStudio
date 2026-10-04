import IngressRouteExtracted

namespace AlloyStudio.Traffic
open AlloyStudio.IngressDecoder AlloyStudio.IngressWire

theorem bool_and_parts (left right : Bool) (h : (left && right) = true) :
    left = true ∧ right = true := by
  cases left with
  | false => cases h
  | true => exact ⟨rfl, h⟩

theorem completed_body_validated (primitives : BodyPrimitives) (input : Inbound)
    (h : completedBody primitives input = true) : BodyValidated primitives input := by
  have outer := bool_and_parts _ _ h
  have inner := bool_and_parts _ _ outer.1
  exact ⟨inner.1, accepted_body_has_bound_parse _ _ _ inner.2,
    AlloyStudio.IngressDeadlines.Spec.check_deadline_sound _ _ outer.2⟩

/-- Original planned parent theorem, now with data-flow binding from raw bytes
rather than independent input flags and byte counts. -/
theorem admitted_is_validated (ipv6 : Octets → Bool) (primitives : BodyPrimitives)
    (input : Inbound) (h : dispatch ipv6 primitives input = true) :
    Validated ipv6 primitives input := by
  unfold dispatch at h
  cases parsed : actualRequest input.wire with
  | none => rw [parsed] at h; cases h
  | some request =>
    rw [parsed] at h
    have outer := bool_and_parts _ _ h
    have middle := bool_and_parts _ _ outer.1
    have inner := bool_and_parts _ _ middle.1
    refine ⟨request, parsed, decoder_sound ipv6 request inner.1,
      (actual_request_binding input.wire request parsed).2.1, inner.2,
      AlloyStudio.IngressDeadlines.Spec.check_deadline_sound _ _ middle.2, ?_⟩
    intro mutation
    have body := outer.2
    rw [mutation] at body
    exact completed_body_validated primitives input body

theorem admitted_with_reachable_capacity (ipv6 : Octets → Bool)
    (primitives : BodyPrimitives) (input : Inbound)
    (limit : Nat) (events : List AlloyStudio.IngressAdmission.ResourceEvent)
    (h : dispatch ipv6 primitives input = true) :
    Validated ipv6 primitives input ∧
    AlloyStudio.IngressAdmission.ResourcesValid limit
      (AlloyStudio.IngressAdmission.Spec.resourceRun limit events AlloyStudio.IngressAdmission.resourcesInitial) :=
  ⟨admitted_is_validated ipv6 primitives input h,
   AlloyStudio.IngressAdmission.Spec.reachable_registry_exact limit events⟩

theorem body_exact_and_bounded (input : Inbound) (h : bodyWithin input = true) :
    0 < declaredLength input.wire ∧ declaredLength input.wire ≤ input.bodyLimit ∧
    input.wire.rawBody.length = declaredLength input.wire := by
  have outer := bool_and_parts _ _ h
  have inner := bool_and_parts _ _ outer.1
  exact ⟨of_decide_eq_true inner.1, of_decide_eq_true inner.2, of_decide_eq_true outer.2⟩

theorem all_lengths_bounded (sizes : List Nat) (limit : Nat)
    (h : sizes.all (fun size => decide (size ≤ limit)) = true) :
    ∀ size, size ∈ sizes → size ≤ limit := by
  induction sizes with
  | nil => intro size member; cases member
  | cons head rest ih =>
    have parts := bool_and_parts _ _ h
    intro size member
    cases member with
    | head => exact of_decide_eq_true parts.1
    | tail _ member => exact ih parts.2 size member

theorem received_headers_bounded (input : Inbound) (h : headersWithin input = true) :
    (∀ size, size ∈ headerLengths input.wire → size ≤ input.lineLimit) ∧
    byteCount (headerLengths input.wire) ≤ input.headerLimit ∧
    (headerLengths input.wire).length ≤ input.headerCountLimit + 2 := by
  have outer := bool_and_parts _ _ h
  have inner := bool_and_parts _ _ outer.1
  exact ⟨all_lengths_bounded _ _ inner.1, of_decide_eq_true inner.2, of_decide_eq_true outer.2⟩

def reviewerPost : Inbound := ⟨lunaPost, 8192, 16384, 100, 0, 5, 16384, 32, 6, 5⟩

theorem reviewer_post_rejected (primitives : BodyPrimitives) :
    dispatch (fun _ => false) primitives reviewerPost = false := rfl

end AlloyStudio.Traffic
