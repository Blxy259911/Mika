# Muse as external brain (muse-brain branch)

Upstream Mika's computer entry points (`mika_run_task`, `/api/v1/tasks`) hand a
whole goal to the phone, and the phone's own model plans and executes it.
This branch adds the opposite mode: **the external caller (Muse) decides every
step; the phone only executes one screen step at a time and returns the
observation.** No step on this path goes through `AgentLoop` / `ModelCaller`.

## What this branch contains

- `core/.../brain/ExternalBrainSessions.kt` (new): a registry around the
  existing model-less `McpToolSession` (`core/.../mcp/McpServer.kt`).
  A session's tool list is frozen at open and filtered to `phone_*` / `web_*`
  only. Model-loop tools (`ask_user`, `hand_to_user`, `end_not_done`,
  `memory_save`, `skill_save`, `settings_set`, …) are never offered.
- `core/.../remote/ExternalBrainApi.kt` (new): HTTP adapter under `/api/v1/brain`:
  `POST /sessions`, `GET /sessions/{id}/tools`,
  `POST /sessions/{id}/step`, `POST /sessions/{id}/close`.
  It reuses `RemoteHost` / `RemoteAccess` authentication and private-network
  restrictions; it opens no new port and no new credential.
- `core/.../remote/RemoteEndpoints.kt` (edited): routes `/api/v1/brain*` to the
  brain API before the task API, and only when a brain registry is supplied.
  When no registry is supplied, the brain entry points do not appear at all.
- `core/.../remote/RemoteTaskApi.kt` (edited): root `GET /api/v1` advertises
  `external_brain` only when a brain registry is wired in. Clients must check
  for it and must NOT silently fall back to `mika_run_task`, whose brain is
  still the phone's own model.
- `core/.../mcp/McpBrainTools.kt` (new, not yet wired): `mika_brain_open` /
  `mika_brain_tools` / `mika_brain_step` / `mika_brain_close`. `McpRpc`
  currently only accepts `McpTaskTools`, so these tools are not yet exposed
  over `/mcp`; HTTP is the primary entry point on this branch.

## What is still required in the app layer (not done on this branch)

`McpRuntime` does not currently pass a `ToolDispatcher` factory to the remote
layer, so `RemoteEndpoints(brain = …)` cannot yet be constructed from the
existing app code. The remaining app step is to extract the factory already
used by `TaskRun` (`toolDispatcher(accessedPhone, …, web = …)`) and pass a
`McpServer` built from it into `RemoteEndpoints`, reusing the same phone/web
capabilities and `ActionExecutor` checks instead of writing a second set.
Likewise, exposing `mika_brain_*` over MCP requires merging the brain tools
into `McpRpc`'s tool set; do not hack it by renaming brain steps as tasks.

Before any release build, run the upstream development checks
(`./gradlew :core:test :core:cliTest :app:testDebugUnitTest :app:assembleDebug`
and the Python tests under `tools/mika/tests`) plus new tests for: tool-list
allow-listing, unknown-tool rejection without execution, and closed-session
rejection. This branch has not yet been through those checks on a real
device; do not ship it as verified until they pass.

## Safety boundaries (unchanged from upstream, plus one user rule)

- Payments, confirmations and phone handover can never be approved remotely.
  A brain step that hits one must stop and hand it to the person on the phone.
- Wake/unlock and gateway device access keep their existing off-by-default
  switches; this branch does not change their defaults.
- **SMS rule set by the user: only verification codes may be viewed; all
  other SMS must not be viewed.** In practice: only use the single code a
  task is currently waiting for; do not open the SMS list, do not read other
  messages, do not store codes, and do not report non-code message content.
  The brain session has no general SMS-reading tool; a code is used only on
  the narrow "this task is waiting for a code" path.
- Do not uninstall apps, clear data, or remove other accessibility services
  (upstream install-with-agent rules apply unchanged).
