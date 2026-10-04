import DecoderModel
import DecoderExtracted
import DecoderContract

namespace AlloyStudio.IngressDecoder

theorem grammar_correspondence : Extracted.grammar = Contract.grammar := rfl
theorem line_rules_correspondence : Extracted.lineRules = Contract.lineRules := rfl
theorem header_rules_correspondence : Extracted.headerRules = Contract.headerRules := rfl

def StrictRequest (ipv6 : Octets → Bool) (input : Input) : Prop :=
  ∀ rule, rule ∈ Contract.lineRules ++ Contract.headerRules →
    holds Contract.grammar ipv6 input rule = true

theorem all_rules_hold (predicate : Rule → Bool) (rules : List Rule)
    (all : rules.all predicate = true) (rule : Rule) (member : rule ∈ rules) :
    predicate rule = true := by
  induction rules with
  | nil => cases member
  | cons head rest inductionHypothesis =>
      cases checked : predicate head with
      | false =>
          change (predicate head && rest.all predicate) = true at all
          rw [checked] at all
          cases all
      | true =>
          cases member with
          | head => exact checked
          | tail _ inTail =>
              change (predicate head && rest.all predicate) = true at all
              rw [checked] at all
              exact inductionHypothesis all inTail

/-- The extracted grammar executes over raw inputs, rather than receiving an
assumed valid-decoder Boolean. Every independent contract rule follows. -/
theorem decoder_sound (ipv6 : Octets → Bool) (input : Input)
    (execution : accepted Extracted.grammar ipv6
      (Extracted.lineRules ++ Extracted.headerRules) input = true) :
    StrictRequest ipv6 input := by
  rw [grammar_correspondence, line_rules_correspondence, header_rules_correspondence] at execution
  intro rule member
  exact all_rules_hold (holds Contract.grammar ipv6 input)
    (Contract.lineRules ++ Contract.headerRules) execution rule member

theorem decoder_matches_contract (ipv6 : Octets → Bool) (input : Input) :
    accepted Extracted.grammar ipv6 (Extracted.lineRules ++ Extracted.headerRules) input =
    accepted Contract.grammar ipv6 (Contract.lineRules ++ Contract.headerRules) input := rfl

theorem missing_http11_host_rejected :
    holds Contract.grammar (fun _ => false) ⟨[71, 69, 84, 32, 47, 32, 72, 84, 84, 80, 47, 49, 46, 49, 13, 10], [], false⟩ .hostRequired = false := by decide

theorem http10_host_optional :
    holds Contract.grammar (fun _ => false) ⟨[71, 69, 84, 32, 47, 32, 72, 84, 84, 80, 47, 49, 46, 48, 13, 10], [], false⟩ .hostRequired = true := by decide

theorem duplicate_host_rejected :
    holds Contract.grammar (fun _ => false)
      ⟨[71, 69, 84, 32, 47, 32, 72, 84, 84, 80, 47, 49, 46, 49, 13, 10], [([72, 111, 115, 116], [97]), ([72, 79, 83, 84], [98])], false⟩ .singletons = false := by decide

theorem tab_separator_rejected :
    holds Contract.grammar (fun _ => false) ⟨[71, 69, 84, 9, 47, 32, 72, 84, 84, 80, 47, 49, 46, 49, 13, 10], [], false⟩ .threeParts = false := by decide

theorem http09_rejected :
    holds Contract.grammar (fun _ => false) ⟨[71, 69, 84, 32, 47, 13, 10], [], false⟩ .threeParts = false := by decide

theorem network_path_rejected :
    holds Contract.grammar (fun _ => false) ⟨[71, 69, 84, 32, 47, 47, 101, 108, 115, 101, 119, 104, 101, 114, 101, 47, 32, 72, 84, 84, 80, 47, 49, 46, 49, 13, 10], [], false⟩ .originOnly = false := by decide

theorem duplicate_charset_rejected :
    regexMatches Contract.grammar.contentType [97, 112, 112, 108, 105, 99, 97, 116, 105, 111, 110, 47, 106, 115, 111, 110, 59, 99, 104, 97, 114, 115, 101, 116, 61, 117, 116, 102, 56, 59, 99, 104, 97, 114, 115, 101, 116, 61, 117, 116, 102, 56] = false := by decide

theorem json_acceptance_requires_all_policies (text : Octets) (limit : Int) (tree : JsonTree)
    (accepted : jsonAccepted text limit tree = true) :
    depthBounded text limit = true ∧ treePolicies tree = true ∧ objectRoot tree = true := by
  unfold jsonAccepted at accepted
  cases depth : depthBounded text limit <;> rw [depth] at accepted
  · cases accepted
  · cases policies : treePolicies tree <;> rw [policies] at accepted
    · cases accepted
    · exact ⟨rfl, rfl, accepted⟩

theorem duplicate_decoded_json_key_rejected (left right : JsonTree) :
    uniqueKeys [([97], left), ([97], right)] = false := rfl

theorem array_json_root_rejected (items : List JsonTree) : objectRoot (.array items) = false := rfl

theorem scalar_json_root_rejected (finiteUnicode : Bool) : objectRoot (.scalar finiteUnicode) = false := rfl

theorem nonserializable_json_scalar_rejected : treePolicies (.scalar false) = false := rfl

theorem lone_surrogate_json_key_rejected : unicodeKey [55296] = false := rfl

theorem json_depth_boundary_accepts : depthBounded [123, 34, 120, 34, 58, 91, 49, 93, 125] 2 = true := by decide

theorem json_depth_overrun_rejects : depthBounded [123, 34, 120, 34, 58, 91, 49, 93, 125] 1 = false := by decide

theorem json_string_braces_do_not_count : depthBounded [123, 34, 120, 34, 58, 34, 123, 32, 91, 32, 92, 34, 34, 125] 1 = true := by decide

end AlloyStudio.IngressDecoder
