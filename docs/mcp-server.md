# MCP server: let an AI assistant manage your medicines

**Audience:** users of this fork who want to read and change their medicines from Claude.
**Type:** how-to and reference. For the design rationale, see [ADR 0006](adr/0006-in-app-mcp-server.md).

medTimer can run a small [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server on your phone.
Claude connects to it and can then list your medicines, show your intake history and adherence, look ahead at the next doses, and create, change, pause and delete medicines and reminders.
The feature is off until you turn it on.

## Read this first: your data leaves the phone

medTimer is otherwise fully offline. This feature is the exception, so understand the path your data takes:

```text
Claude (Anthropic's cloud)  →  your tunnel provider  →  your phone (127.0.0.1)  →  medTimer
```

- Claude's servers contact your phone, not the other way round, so the phone has to be reachable from the internet through a **tunnel that you set up**.
- Anything Claude reads, such as your medicines and intake history, is processed by Anthropic.
- The only credential is the **secret in the connector URL**.
  Anyone who has the URL can read and change your medicines.
  Treat it like a password, and create a new secret if it leaks.
- The app asks for the `INTERNET` permission in this fork.
  Upstream medTimer does not, and the "fully offline" statements in the README do not apply when the feature is on.

## Set up

1. **Start a tunnel on the phone.**
   Any tunnel that gives you a public `https://` address and forwards to `http://127.0.0.1:8765` works.
   The tested way, with a complete step-by-step guide that also covers a second phone and doing it from a laptop over adb, is a named Cloudflare Tunnel run by Termux: see [`mcp-phone-setup.md`](mcp-phone-setup.md).
   The short version:

   ```bash
   pkg install cloudflared
   cloudflared tunnel login
   cloudflared tunnel create medtimer
   cloudflared tunnel route dns medtimer meds.example.com
   ```

   In `~/.cloudflared/config.yml`, send the host to the app:

   ```yaml
   tunnel: medtimer
   credentials-file: /data/data/com.termux/files/home/.cloudflared/<tunnel-id>.json
   ingress:
     - hostname: meds.example.com
       service: http://127.0.0.1:8765
     - service: http_status:404
   ```

   Then run `termux-wake-lock` and `cloudflared tunnel run medtimer`.
   The exact commands change between tool versions, so check the documentation of your tunnel tool.
   A tunnel with a random address that changes on every start means you have to repeat step 3 each time, so prefer a fixed hostname.
2. **Open medTimer → Settings → MCP server.**
3. **Enter the public tunnel address**, for example `https://meds.example.com`.
   The app only keeps the host name and allows it in the server's `Host` check.
4. **Turn on "Enable MCP server"** and confirm the warning.
   A notification "MCP server is running" appears.
   If the server cannot start, the reason is shown under the switch, for example when the port is already in use.
5. **Tap "Copy connector URL".**
   It has the form `https://meds.example.com/mcp/<secret>`.
6. **Add the connector in Claude.**
   On claude.ai, go to **Customize > Connectors**, select **Add custom connector**, and paste the URL.
   Under **Authentication**, choose **No sign-in**.
   See [Add a connector that isn't in the directory](https://claude.com/docs/connectors/custom/remote-mcp).
7. **Ask Claude**, for example "Which medicines do I take, and how well did I stick to them last month?"

> **Mobile app:** Anthropic's documentation says that connections are made from its cloud for every Claude client, including the mobile apps.
> It does not say in so many words that the mobile apps list connectors you add on the web.
> Add the connector on the web, then check whether your phone's Claude app offers it.

The server survives a restart of the app and starts again after a reboot or an app update, as long as it is enabled.
Changing the port, the tunnel address or the secret restarts it by itself.

## Test without a tunnel

Use the "Copy local URL" button, which gives `http://127.0.0.1:<port>/mcp/<secret>`, and connect from a computer with USB debugging:

```bash
adb forward tcp:8765 tcp:8765
curl -si -X POST "http://127.0.0.1:8765/mcp/$SECRET" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

A wrong path answers `404` with an empty body.
For a graphical check, run `npx @modelcontextprotocol/inspector`, pick the **Streamable HTTP** transport and paste the local URL.
For [Claude Code](https://code.claude.com/docs/en/mcp-quickstart):

```bash
claude mcp add --transport http medtimer "http://127.0.0.1:8765/mcp/$SECRET"
```

## Test on an emulator

An emulator needs no phone, no tunnel and no account.
In WSL it needs `/dev/kvm` and your user in the `kvm` group; otherwise it is far too slow.

```bash
sdkmanager "emulator" "system-images;android-28;google_apis;x86_64"
avdmanager create avd -n api28 -k "system-images;android-28;google_apis;x86_64" -d pixel_5
emulator -avd api28 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 &
adb install -r app/build/outputs/apk/full/debug/MedTimer-full-debug.apk
```

Then turn the server on in the app, use `adb forward tcp:8765 tcp:8765`, and test as in the section above.
On a debug build, `adb shell run-as com.futsch1.medtimer cat shared_prefs/mcp_server.xml` shows the secret.
The release build cannot be read that way: tap "Copy local URL" and paste it into a text field instead.
Use `adb -s emulator-5558` if other emulators or devices are connected, so that you do not touch a device that is not yours.

An emulator uses about 4 GB of memory on the host, so stop Gradle daemons (`./gradlew --stop`) while it runs.

## Keep it safe

- **Limit who can reach the tunnel.**
  Claude's requests come from Anthropic's published address range (at the time of writing `160.79.104.0/21`, see [IP addresses](https://platform.claude.com/docs/en/api/ip-addresses)).
  If your tunnel provider has a firewall, allow only that range for your hostname.
  With Cloudflare, add a WAF custom rule that blocks requests to the hostname when `ip.src` is not in the range.
- **Control what Claude may do.**
  Under **Customize > Connectors**, select the connector and set the permission of `delete_medicine` and `delete_reminder` to **Blocked** if you never want Claude to delete.
  Otherwise, read each approval request that Claude shows before you allow it.
  The tools also tell Claude which of them change data, and the instructions ask Claude to confirm with you before deleting.
- **Create a new secret** in the MCP server settings if the URL was shared by mistake.
  The old URL stops working at once, and you add the connector again with the new one.
- **Stop it** with the Stop button of the notification, or by turning off the switch.
- The server only listens on `127.0.0.1`, and requests with a wrong path are answered with an empty `404`.
  Other apps on the same phone could still connect to that address, which is why the secret matters even without a tunnel.
- The secret is kept in its own preferences file and is **not** part of settings backups.

## Tools

All dates are `YYYY-MM-DD`, times are `HH:mm`, and date-times are ISO-8601 in the phone's time zone.
Invalid arguments are refused with a message that names the argument, and nothing is changed.

### Reading

| Tool                  | Arguments                                              | Result                                                                                              |
| --------------------- | ------------------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `list_medicines`      | none                                                   | Id, name, unit, stock (null without stock management), tags, expiration date, number of reminders.  |
| `get_medicine`        | `medicine_id`                                          | One medicine with all details and all its reminders.                                                |
| `list_tags`           | none                                                   | All tags with the ids of the medicines that carry them.                                             |
| `get_intake_history`  | `from`, `to`, `medicine_id?`, `statuses?`              | Reminder events, newest first. At most 1000, and a period of at most 366 days.                      |
| `get_adherence_stats` | `from`, `to`, `medicine_id?`                           | Taken, skipped, unanswered, extra doses and the adherence percentage, overall and per medicine.     |
| `get_upcoming_doses`  | `days?` (1 to 31, default 7), `medicine_id?`           | The doses that the active reminders schedule, and the date each medicine with stock runs out.       |

Adherence counts the doses that a reminder scheduled.
*Unanswered* means a reminder was raised and never marked as taken or skipped.
Doses that you logged by hand are *extra doses* and stay out of the percentage.
Reminders about stock and expiry, and refills, are not doses.

### Changing

| Tool                  | Arguments                                                                       | Notes                                                                                           |
| --------------------- | ------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `create_medicine`     | `name`, and `unit`, `stock`, `refill_size`, `notes`, `tags`, dates, `cannot_be_skipped`, `notification_priority` | Creates a medicine without reminders. Warns about a duplicate name.                    |
| `update_medicine`     | `medicine_id` and the fields to change                                          | Other fields stay. A date set to `null` is removed. `tags` replaces the whole set.              |
| `delete_medicine`     | `medicine_id`, `confirm_name`                                                   | Deletes the medicine and all its reminders. The history of taken doses stays.                   |
| `create_reminder`     | `medicine_id`, `type`, `amount`, and the arguments of the type                  | See below. The reminder starts active.                                                          |
| `update_reminder`     | `reminder_id` and the fields to change                                          | The type cannot change. Arguments of another type are refused.                                  |
| `set_reminder_active` | `active`, and `reminder_ids` or `medicine_id`                                   | Pauses or resumes. With `medicine_id`, all reminders of that medicine.                          |
| `delete_reminder`     | `reminder_id`                                                                   | Also deletes the linked reminders that follow it.                                               |

Reminder types and their arguments:

| `type`                | Required besides `amount`                | Optional                                                                                               |
| --------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `TIME_BASED`          | `time`                                   | `days`, `consecutive_days` with `pause_days` (a cycle), `cycle_start_day`, `period_start`, `period_end`, `active_days_of_month` |
| `CONTINUOUS_INTERVAL` | `interval_minutes`                       | `interval_start` (default now), `interval_starts_from_processed`                                       |
| `WINDOWED_INTERVAL`   | `interval_minutes`                       | `window_start` (default 08:00), `window_end` (default 23:00; an end before the start runs overnight)   |
| `LINKED`              | `linked_reminder_id`, `delay_minutes`    | none                                                                                                   |

All types also take `instructions`, `variable_amount`, `automatically_taken` and `notification_priority`.

## Good to know

- **Marking a dose as taken or skipped is not available** in this version.
- **Notification priority decides whether a reminder pops up.**
  `DEFAULT` is a quiet entry in the notification list.
  `HIGH` is a banner that pops up on the screen with sound, also on the lock screen.
  `HIGH_AND_ALARM` is a full-screen alarm over the lock screen; it needs the permission for full-screen notifications
  (Android 14 and later: Settings → Apps → Special app access → Full screen notifications).
  A medicine has this setting, and each reminder follows it (`SAME_AS_MEDICINE`) unless it has its own.
  It is the same setting as "Notification priority" in the app.
- **Reminders about stock and expiry are managed by the app.**
  Claude can read them, and they are marked `editable_via_mcp: false`, but it cannot create, change or delete them.
  Deleting a medicine removes them together with the medicine.
- **After every change the app plans the next reminder again**, even when the app is closed.
  If that fails, the result carries a warning and opening medTimer once fixes it.
- **Deleting a medicine through MCP removes its reminders and tag links as well.**
  The app's own delete button leaves them behind in the database.
- Changes are applied one at a time, so two requests at once cannot interleave.
- A request body may be at most 256 KiB.

## Troubleshooting

| Symptom                                           | Likely cause and fix                                                                                                                   |
| ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Claude says it cannot reach the MCP server        | The tunnel is not running, your firewall blocks Anthropic's address range, or the server is stopped. Look for the app's notification.  |
| `404` from the URL                                | The secret changed, the URL has a trailing slash, or the path is wrong. Copy the connector URL again.                                  |
| `403` from the URL                                | The host of the URL differs from "Public tunnel address" in the settings.                                                              |
| The switch is on but nothing runs                 | The reason is shown under the switch. A port that is in use needs another port. Android may also have refused a start in the background; open the app. |
| The tunnel stops when the screen is off           | Keep Termux awake with `termux-wake-lock` and exclude it from battery optimization.                                                    |
| The tunnel does not come back after a reboot      | It has to be started again in Termux, see [`mcp-phone-setup.md`](mcp-phone-setup.md#day-to-day).                                        |
| A result carries a warning about the schedule     | The change was saved, but the next reminder could not be planned again. Open medTimer once.                                            |

## Sources

- [Add a connector that isn't in the directory](https://claude.com/docs/connectors/custom/remote-mcp) — Claude documentation, checked 2026-10-11.
- [Authentication for connectors](https://claude.com/docs/connectors/building/authentication) — authentication types and the source address range, checked 2026-10-11.
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) — the server library, version 0.15.0.

_Last reviewed: 2026-10-16._
