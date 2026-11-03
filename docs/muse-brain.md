# External brain (muse-brain branch): any personal assistant as decision layer

Upstream Mika's computer entry points (`mika_run_task`, `/api/v1/tasks`) hand a
whole goal to the phone, and the phone's own model plans and executes it.
This branch adds the opposite mode: **an external caller decides every
step; the phone only executes one screen step at a time and returns the
observation.** No step on this path goes through `AgentLoop` / `ModelCaller`.

The caller is not tied to Muse. Any personal assistant that can speak HTTP
JSON and hold the phone's existing token — Muse, Grok, another bot, a custom
client — can be the decision layer. The protocol carries no assistant
identity check: the caller optionally self-reports a `client` name
(`"muse"`, `"grok"`, `"my-bot"`) when opening a session, only so concurrent
sessions stay distinguishable. Authentication is exactly the upstream
`RemoteHost` / `RemoteAccess` token scheme, shared with the original API.

## Original API: preserved, not replaced

| Entry point | Status on this branch |
|---|---|
| `POST /api/v1/tasks*` and the rest of `RemoteTaskApi` | Unchanged. Still the whole-task route planned by the phone's model. |
| `/mcp` with `McpTaskTools` (`mika_run_task` and the other 6 tools) | Unchanged. `McpBrainTools` is a separate file, not yet merged into `McpRpc`, so the MCP surface today is byte-for-byte the upstream one. |
| `GET /api/v1` root capabilities | Original capabilities plus `external_brain` when a brain registry is wired in. Nothing removed. |
| `GET /api/v1/brain/*` (new) | Additive prefix only. It is routed before the task API only because both share the `/api/v1` prefix; it does not shadow `/tasks`. When no brain registry is supplied, the brain prefix does not appear at all and behaviour is upstream-identical. |
| Multiple assistants at once | Each `POST /api/v1/brain/sessions` creates an independent session (own UUID, own frozen tool list, own step count). `GET /api/v1/brain/sessions` lists live sessions with their `client` so one assistant never has to guess or reuse another's session. Closing one session does not touch the others, and never touches a running `/tasks` job. |

Generic example (any assistant, same token scheme as the original API):

```bash
# 1. Check the patched build advertises external_brain (original root endpoint)
curl -H "Authorization: Bearer $MIKA_TOKEN" http://PHONE:8765/api/v1

# 2. Open a session as Grok (client is self-reported, optional)
curl -X POST -H "Authorization: Bearer $MIKA_TOKEN" -H "Content-Type: application/json" \
  -d '{"goal":"打开设置，查看 WLAN 状态","client":"grok"}' \
  http://PHONE:8765/api/v1/brain/sessions

# 3. One step at a time, decided by the caller
curl -X POST -H "Authorization: Bearer $MIKA_TOKEN" -H "Content-Type: application/json" \
  -d '{"tool":"phone_observe","arguments":{}}' \
  http://PHONE:8765/api/v1/brain/sessions/<session_id>/step

# 4. Original API still works in parallel, unchanged
curl -X POST -H "Authorization: Bearer $MIKA_TOKEN" -H "Content-Type: application/json" \
  -d '{"goal":"..."}' http://PHONE:8765/api/v1/tasks
```

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

## App-layer wiring: landed locally, not yet verified on a build

The screen supplier described below is implemented locally on this branch
(it has NOT been pushed yet, and it has NOT been compiled — see the
verification gap at the end of this section). It is not a one-line change,
for a structural reason found when the wiring was attempted (2026-11-03):

- Upstream production code never constructs `McpServer` at all; it exists
  only in `McpServerTest` with a `FakeScreen`. There is no existing
  `ToolDispatcher` factory in `McpRuntime` to hand over.
- The phone screen a brain session needs (`AndroidScreen` via
  `LazyPhoneScreen`, helper shell, accessibility service, virtual display,
  `AppAccessScreenPort`) is built per `TaskRun` inside `TaskRun.execute`
  (`connectPhone()`), with a `ResourceScope` lifecycle tied to that task.
  `McpRuntime` only holds `tasks` and `device.access`; it has no screen.

What landed (local only, unpushed):

- `app/.../mcp/BrainPhone.kt` (new): the real phone supplier. It mirrors
  `TaskRun.connectPhone` step for step — `PhoneServices.sharedShell`,
  `AccessibilityRepair.awaitService`, `TaskRecovery.beginRun`,
  `LazyAgentDisplay` / `AgentDisplay`, `AndroidScreen`,
  `AppAccessScreenPort`, `AppMuter` (launch-time mute only) — and builds
  `toolDispatcher(accessedPhone, runSpec = spec)` per session. No dummy
  screen, no `FakeScreen`, and it never reuses a running `TaskRun`'s
  dispatcher.
- Timing follows `McpServer`'s contract: `assemble: (RunSpec) -> ToolDispatcher`
  is not suspend, so it only builds the `LazyPhoneScreen` /
  `AppAccessScreenPort` / dispatcher. The phone is actually connected on
  the first phone-needing tool call, when `LazyPhoneScreen` suspend-acquires
  via `connectPhone`, exactly as a task connects on first `launch_app` /
  system command. Read-only screen tools before that behave as in a task
  (empty screen / `no_app_open`), they do not force a connection.
- Lifecycle follows a task's cleanup: each brain session
  (`McpToolSession`'s `RunSpec.id`) gets its own `BrainSession` with its
  own `ResourceScope`, virtual display and `TaskRecovery` registration.
  `ExternalBrainSessions` gained an optional, default-null `onClose`
  hook (the only core change in this step; existing callers and core
  tests are unaffected) so closing a session releases, in task order:
  display apps (`AppsAfterTaskEnd`), mute restore, `TaskRecovery.endRun`,
  `ResourceScope.close` (virtual display), `sharedShell.release`, and
  the package-reservation owner. A failed connect releases the shell
  occupation and recovery registration it already took, so a retry does
  not double-occupy the shared shell. A session that never connected
  releases nothing but an empty scope.
- Package reservations use the same `MikaApp.resources` table that
  `TaskCenter` uses, with the holder check extended to live brain
  sessions and live tasks, so a brain session and an ordinary task
  cannot silently grab the same app from each other. A second virtual
  display that cannot be created is reported as `PhoneUnavailable`
  (existing `parallel_second_display_failed` text) for that tool call
  and retried on the next call; there is no queueing, and the session
  itself is not invalidated by it.
- `MikaApp` now wires it in: `brainPhone` is built lazily from
  `MikaApp` + `remoteTasks` (the `McpTaskHost`), and `mcp` passes
  `brain = brainPhone.brain` to `McpRuntime`, which forwards it to
  `RemoteEndpoints`. That is what makes `/api/v1/brain` and the
  `external_brain` root capability appear on a build from this branch.
  `McpRuntime`'s default is still null, so callers that pass nothing
  keep upstream behaviour.
- Deliberate limits of this wiring, stated plainly: it provides phone
  tools only (no `WebPort`, so no `web_*` tools yet, although the core
  allow-list still admits the `web_*` prefix); it does not publish PiP
  frames and does not run `TaskRun`'s `watchTarget` auto-mute for apps
  that appear without being launched; and it has no takeover state, so
  `AndroidScreen.handedOver` is always false — a brain session assumes
  the user is not simultaneously operating the main screen.
- Likewise, exposing `mika_brain_*` over MCP requires merging the brain
  tools into `McpRpc`'s tool set; do not hack it by renaming brain steps
  as tasks.

Verification gap — read this before claiming anything works:

- The existing `.github/workflows/core-test.yml` only runs `:core:test`.
  It covers the core `onClose` change (default null, existing tests
  unchanged) once pushed, but it does NOT compile the app module, so
  `BrainPhone.kt`, the `MikaApp` wiring and the `McpRuntime` comment
  change are not covered by it.
- The author's sandbox has no Android SDK and cannot run Gradle at all
  (see the core-verification note below), so the app module has NOT
  been compiled locally either. Do NOT claim the app wiring compiles
  until `./gradlew :app:assembleDebug` (or at least
  `:app:compileDebugKotlin`) has run on a machine with an Android SDK,
  or the workflow has been extended to cover it. The signatures used
  here were checked by reading the source files listed in the wiring
  notes, not by a compiler.

Core-level verification on this branch (no Android device needed):
`core/src/test/.../brain/ExternalBrainSessionsTest.kt` covers, on a
`FakeScreen`: tool-list allow-listing, unknown-tool rejection without
execution, concurrent assistant sessions staying independent, and the HTTP
open / list / close contract including unknown-field rejection.
Run it with `./gradlew :core:test` once a JDK 17 + Android SDK are present.
Note (2026-11-03): the author's sandbox cannot run Gradle at all — a
trivial Gradle project fails the same way (daemon starts, client never
receives a message), so local compilation is currently unverified for
environmental, not code, reasons. The verification path is
`.github/workflows/core-test.yml`, which runs the brain + MCP tests on a
GitHub runner on every push to this branch; check its Actions result
before claiming the branch compiles.

Before any release build, run the upstream development checks
(`./gradlew :core:test :core:cliTest :app:testDebugUnitTest :app:assembleDebug`
and the Python tests under `tools/mika/tests`). This branch has not yet
been through those checks on a real device; do not ship it as verified
until they pass, and do not install it on a phone as if the brain entry
point were live — the app-layer wiring above is implemented locally but
unpushed and uncompiled, so it will not appear until it is pushed, its
app-module compilation is verified, and a build is produced from it.

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

## Root grant patch, muse-brain only (user instruction, 2026-11-03)

The owner asked whether Mika's source can call root directly, and then
chose the narrow scope first: a root grant patch only, not a root-started
helper.

What it does: `app/.../cap/phone/setup/RootGrant.kt` runs at
`BuiltinShellRecovery.start()`, in the background, only when Mika does not
already hold `WRITE_SECURE_SETTINGS`. It tries `su` candidates in order
(`su`, system su paths, KernelSU and Magisk paths) and runs exactly one
command as root: `pm grant` for Mika's own package and that one permission.
That permission is the same one Mika's recovery already asks for through
the helper shell; on the owner's OnePlus phone that helper-shell path fails
because the helper shell lacks `GRANT_RUNTIME_PERMISSIONS`, while root can
grant it. On success, and only for capabilities the user has not declined,
`BuiltinShellRecovery` marks `RestoreAfterReboot` and
`AutoWirelessDebugging` applied, so the existing wireless-debugging auto-on
and boot-restore gates can use the permission. It does not grant anything
to another app, run any other command, or start the helper as root.

Why not a root-started helper: the helper handoff only accepts shell uid
2000, so a helper started as root uid 0 would be rejected by
`HelperHub.offer`. A root-started helper would have to drop to shell uid
and carry a nonce issued by Mika itself, which is a larger, separate change
and is not claimed here.

Failure is silent by design: no root, root denied, or no su binary all just
leave Mika on the old helper-shell path, exactly as before. Reversible:
`pm revoke` for Mika's own package and that permission. This patch is on
the muse-brain branch only; it is not proposed to upstream, which has no
root path.
