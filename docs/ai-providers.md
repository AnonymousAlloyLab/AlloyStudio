# AI guidance and candidate-review providers

Alloy Studio supports OpenAI Responses, Anthropic Claude Messages, Google Gemini
`generateContent`, DeepSeek chat completions, and an explicitly configured
OpenAI-compatible chat endpoint. Provider keys and role configuration belong to
the **private deployment configuration**, never the learner portal. The optional
learner chatbot offers a fixed model selector for privately enabled providers;
it exposes display metadata only. Each provider request uses one model. There
is no automatic fallback, ensemble or provider retry.

## Configure once per deployment

Create `ai.local.json` beside `server.py` (`backend/ai.local.json` in an IIS
installation). Start with `ai.example.json`; it contains empty credentials only.
On Linux/macOS, create it privately before editing:

```sh
install -m 600 ai.example.json ai.local.json
```

For IIS, place the file under the installed private backend, using its existing
administrator/task-account ACLs. Keep it outside `wwwroot`. Restart the backend
and keep the existing `-EnableLuna` installation option enabled: this legacy flag
now enables all configured AI roles. An upgrade must preserve `ai.local.json` and
its private key files alongside `openai.local.json` and `admin.local.json`.

A minimal example selecting Claude for guidance and OpenAI for review is:

```json
{
  "version": 1,
  "profiles": {
    "guide": {
      "provider": "anthropic",
      "model": "claude-haiku-5-5",
      "api_key_file": "secrets/anthropic.key",
      "reasoning_effort": "low"
    },
    "reviewer": {
      "provider": "openai",
      "model": "gpt-6.1-sol",
      "api_key_file": "secrets/openai.key",
      "reasoning_effort": "high"
    }
  },
  "roles": {"guidance": "guide", "upload": "guide", "review": "reviewer"}
}
```

Use either `api_key` or `api_key_file` in each profile, never both. Relative key-file paths resolve beside the config. Protect those files
with mode `600` on Linux/macOS too. `ALLOY_AI_CONFIG_FILE` can select an alternate
private config outside IIS; relative config paths resolve beside the backend,
not the shell's working directory. The IIS launcher deliberately selects its
installed backend file instead of inheriting this override.

`guidance` explains the learner's individual edits and displayed instances;
`upload` suggests public titles/questions around immutable uploaded Alloy code;
`review` supplies private advice when an administrator clicks **Review with AI**.
Set the role's profile name to switch models. Missing roles are disabled, except
that upload inherits guidance when omitted. Set a role to `null` to explicitly
disable it, including upload. An empty selected credential disables
that role. Malformed explicit configuration fails closed. `ALLOY_AI_DISABLED=1`
or the legacy `OPENAI_DISABLED=1` disables AI in production. The browser cannot
read or edit credentials, profile names, endpoints or role configuration.

Without `ai.local.json` or an explicit new config path, legacy OpenAI configuration
continues to work: GPT-6 Luna with low effort for guidance/uploads, GPT-6.1 Sol
with high effort for candidate review. An explicit new config takes precedence;
it never silently borrows the legacy OpenAI credential.

## Learner chatbot model selection

The side-panel tutor offers GPT-6 Luna, GPT-6.1 Terra and GPT-6.1 Sol for an enabled
OpenAI credential; Claude Haiku, Sonnet and Opus 5.5 for Anthropic; and Gemini 3.8
Flash and Gemini 4 Argon for Gemini. The public catalogue lists these exact model
IDs, labels, providers and supported selections. It does not make a paid model
probe or promise access on a particular provider account. A provider rejection
does not switch to another model or account.

Without a `chat` member, only the existing **guidance** profile's provider stack
is enabled. With legacy OpenAI configuration, its existing credential enables
the OpenAI stack. Upload-only and review-only credentials never enable learner
chat implicitly. To authorize multiple providers or a separate billing account,
bind each provider to an existing private profile:

```json
"chat": {
  "openai": "luna",
  "anthropic": "haiku",
  "gemini": "flash"
}
```

This is an optional top-level member of `ai.local.json`; the profile names must
exist and match the provider. It authorizes the listed provider's full fixed chat
stack using that profile's credential, independently of the profile's normal
role model. Missing providers are disabled when this map is present. Set
`"chat": null` or `"chat": {}` to disable the tutor; a provider value of `null`
disables that provider. Empty keys disable their entries. Unknown providers,
missing profiles and provider/profile mismatches fail closed. Existing guidance,
upload and review role selections retain their configuration.

For Luna and Terra, the chat selector supports `none`, `low`, `medium`, `high`,
`xhigh` and `max`; Sol supports `low`, `medium`, `high`, `xhigh` and `max`. The
default is `low`. Anthropic and Gemini use one fixed setting per model: Haiku and
Sonnet `low`, Opus `high`, Flash `minimal`, and Argon `low`.

Each suggestion request uses the least-expensive model in the selected requested
provider stack: Luna at `none`, Haiku at `low`, or Flash at `minimal`. It retains
the **same account credential and provider** as the selected tutor, even if the
deployment configuration changes during that pass. It is a separate bounded
request for up to three suggested questions; it is not a fallback for the main
answer. Credentials stay server-side throughout selection and transport.

Chat model, provider and reasoning changes preserve the conversation and unsent
message. The next send shares that retained discussion and the current learner
context with the newly selected configured provider. Each reply identifies its
model and effort. Changing a selection makes no API call; suggestions are refreshed
on request using the new provider's inexpensive model. Use **New chat** to clear
the discussion before switching if you prefer not to carry it to another provider.

## Model identifiers

The example template lists these exact API identifiers, verified against official
documentation (chat selections updated on 2026-10-27). They are configurable strings, not a promise of
account access:

- OpenAI: `gpt-6-luna`, `gpt-6.1-terra`, `gpt-6.1-sol`, `gpt-6-astra`. Use high effort for Sol/Astra
  reviews. [OpenAI Luna](https://developers.openai.com/api/docs/models/gpt-6-luna),
  [Terra](https://developers.openai.com/api/docs/models/gpt-6.1-terra),
  [Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol),
  [Astra](https://developers.openai.com/api/docs/models/gpt-6-astra).
- Anthropic: `claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-opus-5-5`.
  [Claude model catalog](https://platform.claude.com/docs/en/models/overview).
- Google: `gemini-3.8-flash`, `gemini-4-argon`. Argon is documented as a paid API
  model, without a free tier.
  [Gemini catalog](https://ai.google.dev/gemini-api/docs/models),
  [Argon availability](https://ai.google.dev/gemini-api/docs/latest-model).
- DeepSeek: **`deepseek-flash`** is the documented API alias for
  DeepSeek-V4.1-Flash. [DeepSeek model mapping](https://api-docs.deepseek.com/quick_start/pricing/).

For another provider, use `provider: "openai-compatible"`, its exact `model`, and
an `endpoint` containing its full HTTPS chat-completions URL. Only this provider
kind accepts an endpoint override. This adapter requires chat-completions JSON
object output; compatibility is not guaranteed for every service. Keep the
endpoint free of query parameters, fragments and credentials. Treat this private
configuration as authority to send that role's data to that recipient.

Reasoning controls vary by provider. OpenAI uses `reasoning.effort`, Claude uses
`output_config.effort`, Gemini uses `thinkingConfig.thinkingLevel`, and DeepSeek
uses `reasoning_effort`. A generic compatible endpoint omits reasoning controls
unless explicitly configured. Values unsupported by a model can be rejected by
its API; Alloy Studio does not substitute another model or silently retry.
See [Claude effort](https://platform.claude.com/docs/en/build-with-claude/effort),
[Gemini generateContent](https://ai.google.dev/api/generate-content), and
[DeepSeek chat API](https://api-docs.deepseek.com/api/create-chat-completion/).

## Data and operational boundaries

Learner guidance receives only the existing allowlist: learner raw/canonical
code, public question, permitted operator hints and public instance evidence.
It receives no oracle or correct-pool bodies. Guidance remains prose about what
to inspect; it cannot alter solver results, scores or candidate admission.

Upload suggestions disclose the uploaded source to the configured upload
provider. **Review with AI** discloses the candidate, its environment and teacher
solutions to the configured review provider. Viewing candidates makes no model
call. Advice cannot approve or dismiss anything. Administrator approval still
requires a fresh bounded Alloy check. High effort and bounded agreement are not
unbounded equivalence proofs.

All adapters retain local schema, identity, length and prose checks. Provider
structured-output schemas are adapted to supported dialects; DeepSeek and generic
chat use JSON-object output plus the same local validators. Truncation, refusal,
tool calls and malformed outputs fail safely. OpenAI requests retain
`store: false`; that flag is not a cross-provider data-retention guarantee.
Check the chosen provider's account and retention settings separately.

Existing timeout/concurrency/token/response limits remain. A slower reasoning
model may return unavailable within these limits; deterministic hints continue.
No model selection launches another JVM. Guidance caching is scoped to provider,
endpoint, model, reasoning configuration and credential digest, in addition to
exact learner evidence. Restart after deployment config changes to clear any
in-flight work and make the new selection unambiguous.

Keys are read server-side, sent only in authentication headers, and never printed
in error messages. Redirects are refused. The config is Git-ignored, excluded from
packages and blocked by static-file allowlists. Accepted text is redacted for the
exact active key. Native provider endpoints use HTTPS. There are no tokens in
the template and no secret files in the public source release.

The [acceptance contract](ai-providers-spec.md) records the invariants and test
scope. Adapter tests use synthetic credentials/responses; account availability
and paid live requests are not claimed by those tests.
