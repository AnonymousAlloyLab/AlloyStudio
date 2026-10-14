# Private AI provider selection

This contract governs the v0.0.7-alpha.4 provider extension. It preserves the
deterministic Alloy engines, scoring, candidate admission and administrator
approval requirements.

1. **Deployment authority.** Only a private backend file selects the provider,
   model, credential and reasoning setting for each role: `guidance`, `upload`,
   and `review`. Browser requests cannot supply credentials, endpoints or model
   overrides. One model is invoked per role/request; selecting another model
   requires changing deployment configuration. There is no automatic ensemble,
   retry against another provider, or extra paid request.
2. **Compatibility and failure.** Without the new file, the existing OpenAI
   configuration and defaults remain supported. An explicitly selected invalid
   configuration fails closed; it cannot fall back to another credential or
   provider. Disabled AI leaves deterministic hints and manual administration
   usable. The legacy deployment disable switch also disables new providers.
3. **Private credentials.** `ai.local.json` is ignored by Git, excluded from
   packages and outside the static web allowlist. POSIX files require private
   permissions; IIS relies on its restricted backend ACL. Relative key-file
   paths resolve beside the configuration. Requests use TLS and authentication
   headers; credentials never appear in URLs, process arguments or diagnostics.
   Redirects cannot forward credentials. Exact active keys are removed from
   accepted provider text. Templates contain no credentials.
4. **Role-specific disclosure.** Guidance receives only the existing allowlisted
   learner evidence and public question, never oracle bodies or hidden target
   expressions. Upload suggestions receive the uploaded model for metadata only.
   Review receives the private candidate, environment and teacher solutions only
   after the administrator clicks Review. The configured provider is a recipient
   of that role's data; deployment owners must choose it accordingly.
5. **Untrusted output.** All adapters normalize complete text responses into the
   existing local validators. Tools, refusals, truncation, unexpected identities,
   missing descriptions and malformed JSON are rejected. Provider JSON schema
   support supplements local validation; it does not replace it. Guidance keeps
   its no-solution instructions and bounded prose checks. No claim is made that
   arbitrary model prose can be semantically proven solution-free.
6. **Resource invariance.** Existing byte, deadline, concurrency and cache limits
   remain. Guidance cache identity additionally includes provider, endpoint,
   exact model, reasoning setting and a digest of the active credential. Changing
   any of these cannot reuse another configuration's explanation. No extra JVM
   or solver work is introduced by model selection.
7. **Admission authority.** Advice cannot insert, approve or dismiss a candidate.
   The existing reward/counterexample admission gate and fresh bounded Alloy
   check on administrator approval remain mandatory for every provider.
8. **Availability claims.** Model names are exact API identifiers supplied by the
   owner. Documentation availability does not establish access for a particular
   account. Unsupported models fail visibly and safely, without substitution.

Validation covers synthetic provider requests/responses, private configuration
and failure paths, cache separation, unchanged guidance/advice validators,
browser labels, and portable package inclusion/exclusion. These are executable
regressions; this extension does not assert a new Lean proof or rebind historical
formal evidence.
