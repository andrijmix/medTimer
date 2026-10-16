# Run an optional MCP server inside the app

## Context

The goal is to read the adherence statistics and to change medicines and reminders from Claude on the phone, without opening medTimer.

- A file export cannot do this: a backup is read-only, and the changes would have to be imported again by hand.
- The app had no integration surface at all: no exported component, no content provider, no automation intent.
- Claude's apps do not connect to a phone. Custom connectors are called from Anthropic's cloud (`160.79.104.0/21`) and need a public HTTPS address.
  They support OAuth or no authentication; a fixed token in a header is a beta for selected organizations.
- Upstream medTimer promises "full offline privacy" and removes `INTERNET` from the merged manifest.
  This work is for a personal fork, so that promise is changed on purpose, for this fork only.

## Decisions

### 1 — The server runs in the app, behind a foreground service

`:feature:mcp` starts a Ktor (CIO) engine with the official MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk-server` 0.15.0).
A foreground service of type `specialUse` keeps the process alive.
It restarts after a reboot and an app update (`McpBootReceiver`), and `McpServerController` retries when the app comes to the foreground, because Android 12+ refuses
foreground-service starts from the background.
`McpBootReceiver` is not a Hilt entry point: it reads the enabled flag straight from the settings file.
Hilt test applications are not initialized when a package-replaced broadcast arrives, which is why the debug manifest removes the app's own `Autostart` and `TimeChangeReceiver`.
An injected receiver would fail in the same way during instrumented tests, and this one needs nothing but one boolean.
Everything is off until the user enables it in the settings.

### 2 — The server listens on loopback only; the tunnel belongs to the user

The app binds `127.0.0.1` and never `0.0.0.0`.
Making the phone reachable is the job of a tunnel that the user runs (Termux with `cloudflared`, ngrok, …).
It is documented in [`mcp-server.md`](../mcp-server.md) and is not part of the app: this keeps a cloud relay, and its trust and cost, out of the codebase.
The public host of the tunnel is a setting, because the SDK's DNS-rebinding protection rejects every other `Host`.

### 3 — Authentication is a 256-bit secret in the URL path

The connector is added as "No sign-in", and the path `/mcp/<secret>` is the credential.
`SecretPathGate` compares SHA-256 digests of the path with `MessageDigest.isEqual`, answers every other path with an empty `404` before routing, and rejects bodies over 256 KiB.

Alternatives that were rejected:

- OAuth with a PIN page in the app: more code and a second web surface on the phone, and the PIN would not protect more than the URL does for a personal fork.
- A static token in a request header: Anthropic offers it as a beta for a limited set of organizations only.

The cost is that a leaked URL gives full access until the secret is replaced.
The secret has its own preferences file, is excluded from backups, and is redacted in every `toString`.

### 4 — Stateless Streamable HTTP

`mcpStatelessStreamableHttp` keeps no `Mcp-Session-Id`, so a restart of the service or the process leaves no stale sessions.
The SDK opens and closes a session for each request on one shared `Server`.
Verified by test: `tools/list` and `tools/call` work on a fresh session without `initialize`.

### 5 — Module layout

`:feature:mcp` depends on `:core:*` and on `:feature:reminders:api`, never on `:feature:ui` or the `:feature:reminders` implementation.
The settings screen lives in `:feature:ui` and only edits `McpSettingsDataSource`; the server follows those settings by itself.
This avoids a feature-to-feature edge in either direction.
`McpPreferencesModule` is separate from `DatastoreModule` because Hilt tests replace `DatastoreModule` and have no use for these preferences.

### 6 — Every change goes through `WriteCoordinator`

`ReminderSchedulerService` only reschedules while the app's screen is open, so a change made over MCP has to ask for it.
`WriteCoordinator` runs changes one at a time and calls `ReminderCommandBus.scheduleNextNotification()` afterwards, also after a failure half way.
A `ToolArgumentException` means that nothing was written yet and does not reschedule.
A failed reschedule becomes a warning in the result, not a failed call, because the model would otherwise retry a change that was already saved.

### 7 — Writes copy what the app's screens do

New medicines use the highest sort order as it is, new reminders start from `Reminder.default()` with `createdTime = now` and `cycleStartDay = tomorrow`, and
pausing uses `setRemindersActive`.
The one deliberate difference is `delete_medicine`: it also removes the reminders and tag links, because the database has no foreign keys and the app's own
delete leaves them behind.

### 8 — Stock and expiry reminders are read-only

They are generated and driven by the app.
Tools report them with `editable_via_mcp: false` and refuse to change or delete them.

### 9 — Failures never reach the model or the crash handler unfiltered

- `defineTool` turns an unexpected exception into a fixed message with the type only; messages of the database can contain stored values.
- The engine runs in a scope with a `CoroutineExceptionHandler`.
  Without it, a failed bind (a port in use) is an uncaught exception in a Ktor thread, which ends the app on Android.
  `McpHttpServerTest` reproduces this and fails without the handler.
- Logs contain exception types only.
  No SLF4J binding may be added; without one, the SDK's and Ktor's logging is a no-op.

### 10 — A documented exception to the translation rule

`strings_mcp.xml` carries `tools:ignore="MissingTranslation"`, with a comment.
The project translates every string into every locale, which is not practical for an opt-in feature of a fork.
English is the fallback, Ukrainian is provided, and the ignore goes away once Weblate has the other locales.

## Consequences

- With the feature on, medication data goes to Anthropic and through the tunnel provider.
  The README's offline statements are not true for this fork when it is enabled.
- The APK grows by the Ktor and MCP SDK dependencies.
  R8 removes unused SDK classes; `consumer-rules.pro` only silences `java.lang.management`, which Ktor guards with `try/catch`.
- `AGENTS.md` forbids sending real medication data to external services.
  This feature does so only when the user turns it on, and only to the assistant that the user connects.
- A tool is one `defineTool` call, and a group of tools is one `@ElementsIntoSet` function in `McpToolGroupsModule`.
  `ToolCatalogTest` checks the invariants of all tools (unique names, described arguments, honest read-only and destructive hints).

## Verification

JVM tests cover the routing, the secret gate, the real CIO engine on loopback sockets, every tool and the writers against an in-memory database.
An emulator run (2026-10-13) covered the rest, with the debug build on API 28 and 36 and the R8-minified release build on API 28:

- Ktor CIO starts on API 28, the oldest supported version, and on API 36.
- The foreground service runs as `specialUse`, shows its notification, and the Stop action turns the server off for good.
- The server comes back after a reboot and after an app update without the app being opened, on API 28 and, for the reboot, on API 36.
- A used port is reported under the switch and does not crash the app; a changed port, and a new secret, restart the running server.
- The settings screen, the consent dialog and the screen navigation work on both versions, and with notifications denied the server still runs on API 36.
- Reminders made through MCP fire like any other and the next alarm is planned again, also when the app is in the background; pausing a reminder removes its alarm and resuming it brings the alarm back.
- Deleting a medicine through MCP leaves no reminders or tag links in the Room database and keeps the event history.
- The release build answers every tool, so R8 removed nothing that the protocol needs.

## Verification on a phone

On 2026-10-16 the release build ran on a Samsung phone with Android 16, was updated in place with the same signing key, and was reached through a named Cloudflare Tunnel run by `cloudflared` from Termux (F-Droid).
The user confirmed that Claude then worked with the connector.
Ngrok's free plan, ngrok's Java library inside the app and Cloudflare quick tunnels did not work; the reasons and the working procedure are in [`docs/mcp-phone-setup.md`](../mcp-phone-setup.md).

## Not verified yet

- Battery use and Doze over days, in particular whether requests reach the phone with the screen off for a long time.
- Claude itself on a phone: Anthropic does not say that the mobile apps list connectors that were added on the web.
- Starting the tunnel at boot (the Termux:Boot add-on).
- The debug build restores no reminder alarms after a reboot, because the debug manifest removes `Autostart`; that is upstream's choice for tests and the release build restores them.
  Use a release build for daily use.
- Writes were tested on the emulator's real Room database, but not with a large amount of data.
