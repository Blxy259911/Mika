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

## Autonomous mode for the owner's own Android (user instruction, 2026-11-03)

The user instructed: cancel the operational restrictions on their own
Android, so that a command they issue runs on Android as issued, including
syntactically valid shell commands, uninstalling a named app, changing a
named setting, and installing a named APK.

In Muse's layer this means: for operations **the user explicitly issues for
their own device**, Muse adds no extra per-step confirmation gate and no
extra allow-list beyond the brain session's `phone_*` / `web_*` tools.
`phone_system_command` is already a `phone_*` tool, so a user-issued Android
shell command (including `su -c …` on a rooted device the user owns) goes
through that existing tool, and its output is returned and logged. Muse
will not invent a high-risk operation on its own: uninstall / settings /
install / shell actions happen only when the user names them.

Remaining code note (not yet removed): upstream `PhoneCommand` still steers
shell commands whose purpose is tap / type / key / launch / share back to
the dedicated phone tools (`use_phone_tool`). In autonomous mode Muse does
not stop on that refusal; it achieves the same effect with the dedicated
tool. Removing that phone-side steering for brain sessions is a separate,
scoped code change, not claimed done here.

## Hard floors (not cancelled by the autonomous instruction)

- Commands targeting other people or systems, illegal acts, malware, or
  attacks are not executed, even if syntactically valid.
- Payment authentication itself is done by the bank / payment app on the
  phone; Muse cannot approve a payment on the user's behalf. That is the
  provider's confirmation, not a Muse gate.
- **SMS rule set by the user on 2026-11-03 is kept as a separate privacy
  rule: only verification codes may be viewed; all other SMS must not be
  viewed.** The autonomous instruction above is read as cancelling Muse's
  operational confirmation gates, not as cancelling that privacy rule.
  If the user explicitly cancels the SMS rule too, record that separately.
- Wake/unlock and gateway device access keep their existing off-by-default
  switches; this branch does not change their defaults.
- Irreversible effects (deleting data, uninstalling, system settings) are
  still reported with their exact command and effect before running when
  the command is destructive; for a user-issued command that is the report
  itself, not an extra approval gate, unless the command targets the system
  partition or a system-critical package, in which case Muse stops and
  asks first.
