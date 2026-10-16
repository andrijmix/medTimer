# Set up the MCP server on a phone with a Cloudflare Tunnel

This is the tested way to make a phone reachable by Claude.
[`mcp-server.md`](mcp-server.md) describes what the server does, its tools and how to keep it safe; read its first section before you start, because your medication data then travels through your tunnel provider and Anthropic.

It was tried on 2026-10-16 with the release build of the `full` flavor on a Samsung phone with Android 16, Termux from F-Droid, and a named Cloudflare Tunnel.
Each phone gets its own tunnel, host name, secret and connector.

```text
Claude (Anthropic's cloud)
   →  https://meds1.<your-domain>/mcp/<secret>       Cloudflare edge
   →  tunnel "medtimer-phone1"                        cloudflared in Termux, on the phone
   →  http://127.0.0.1:8765/mcp/<secret>              medTimer MCP server, on the phone
```

Nothing here opens a port on your router, and the phone may be on any network.

## What you need

- A **Cloudflare account** (free) and a **domain that uses Cloudflare's name servers**.
  You can buy one in the Cloudflare dashboard (**Add a Site**, then register a new domain), which Cloudflare sells [at the registry's price without a mark-up](https://www.cloudflare.com/products/registrar/).
  A domain you already own works too: add it as a site on the free plan and switch its name servers at your registrar.
- The **signed medTimer APK** (see [Get the APK](#get-the-apk)).
- A phone with **Android 9 or later and a 64-bit ARM processor**.
  The `cloudflared` package of Termux is built for `aarch64` only.
- For the adb method: a laptop with `adb` (Android platform-tools) and the phone on the same Wi-Fi.

Pick the names once and keep to them:

| Phone | Tunnel name        | Host name                | Connector name in Claude |
| ----- | ------------------ | ------------------------ | ------------------------ |
| 1     | `medtimer-phone1`  | `meds1.<your-domain>`    | `MedTimer 1`             |
| 2     | `medtimer-phone2`  | `meds2.<your-domain>`    | `MedTimer 2`             |
| N     | `medtimer-phoneN`  | `medsN.<your-domain>`    | `MedTimer N`             |

The tools of every phone have the same names, so give each connector a different name.
Otherwise Claude cannot tell which phone it talks to.

## Get the APK

Copy the signed APK from the machine that built it, or build and sign your own:

```bash
./gradlew :app:assembleFullRelease
# the key is created once; keep it and its password out of the repository and back them up
keytool -genkeypair -v -keystore medtimer-personal.jks -alias medtimer -keyalg RSA -keysize 4096 -validity 36500
zipalign -f -P 16 4 app/build/outputs/apk/full/release/MedTimer-full-release-unsigned.apk aligned.apk
apksigner sign --ks medtimer-personal.jks --out MedTimer-full-release-signed.apk aligned.apk
apksigner verify --print-certs MedTimer-full-release-signed.apk
```

Use the **same key for every phone and every later update**.
Android only updates an app in place when the signature is the same; with another key you have to uninstall first, which deletes the data.
The key is a secret: never commit `*.jks` or its password (see [AGENTS.md](../AGENTS.md#ai-hygiene)).
Use a release build for daily use, because the debug build does not restore reminder alarms after a reboot.

## Move the medicines to a new phone

On the old phone use the app menu → **Backup**; on the new phone use **Restore** (Ukrainian UI: «Підкріплення» and «Відновити»).
Restore replaces all medicines, reminders and tags (and the history, if you restore events), so do it on the empty new phone first.
The file contains medication data, so delete it from chats and cloud drives afterwards.
The MCP settings and the secret are not part of a backup; set them up on each phone.

After a restore, open the screen with the upcoming reminders and check that the next dose is planned.
If it is not, close the app completely and open it again.

## Do it by hand on the phone

1. Install the APK and restore the backup.
   In the phone's battery settings choose **Unrestricted** for medTimer.
2. Install **Termux from F-Droid** (`f-droid.org/packages/com.termux`).
   Do not use the Google Play version; it is outdated.
   If the installation stops with a verification message, try again and confirm the Play Protect prompt.
   Choose **Unrestricted** for Termux in the battery settings as well.
3. Open Termux and wait for the `~ $` prompt.
   Then run the commands below, one block at a time.
   When `pkg upgrade` asks about a configuration file (`[default=N] ?`), press Enter to keep the current one.

   ```bash
   pkg update -y && pkg upgrade -y
   pkg install -y cloudflared
   cloudflared tunnel login
   ```

   `login` prints a link.
   Long-press it, choose **Select URL**, open it in the browser, sign in, choose your domain and tap **Authorize**.
   Termux then says `You have successfully logged in`.
4. Create the tunnel and its DNS name (use your numbers):

   ```bash
   cloudflared tunnel create medtimer-phone2
   cloudflared tunnel route dns medtimer-phone2 meds2.<your-domain>
   ```

5. Write the configuration and the start script, remove the login certificate, and start the tunnel:

   ```bash
   ID=$(basename ~/.cloudflared/*.json .json)
   cat > ~/.cloudflared/config.yml <<EOF
   tunnel: $ID
   credentials-file: $HOME/.cloudflared/$ID.json
   ingress:
     - hostname: meds2.<your-domain>
       service: http://127.0.0.1:8765
     - service: http_status:404
   EOF
   rm ~/.cloudflared/cert.pem
   echo "termux-wake-lock" > ~/start-tunnel.sh
   echo "cloudflared tunnel --no-autoupdate run medtimer-phone2" >> ~/start-tunnel.sh
   nohup bash ~/start-tunnel.sh > ~/tunnel.log 2>&1 &
   ```

   The check below should print `4`:

   ```bash
   grep -c "Registered tunnel connection" ~/tunnel.log
   ```

6. In medTimer open **MCP server** in the settings (Ukrainian UI: «MCP-сервер») and do, in this order:
   - **Public tunnel address**: `https://meds2.<your-domain>`
   - **Create new secret**
   - **Enable MCP server**, and confirm the warning
   - **Copy connector URL**
7. Add the connector in Claude, see [Add the connector](#add-the-connector).

`cert.pem` lets whoever holds it manage DNS for your domain, and it is only needed to create the tunnel, so step 5 deletes it.
The file `~/.cloudflared/<id>.json` is the tunnel's own secret, and it has to stay in Termux.

## Do it from a laptop with adb

This is the quickest way, and a coding agent such as Claude Code can run it on the laptop.
You are needed for four things: switch on wireless debugging and read out the pairing code, unlock the phone, authorize Cloudflare in the browser, and tap the settings in medTimer.

### Rules for an agent

- Touch only Termux, the Cloudflare authorization page and the medTimer settings.
  If another app is in front, stop and ask the user to open Termux; do not look at or comment on other apps.
- Check that Termux is in front **before every line you type** (the helper below does).
- Never ask for, print or store: the Cloudflare password, the contents of `~/.cloudflared/*.json`, the connector URL or secret, the ngrok or Cloudflare tokens.
  The link printed by `cloudflared tunnel login` carries a one-time token, so open it on the phone without printing it.
- Take no screenshot while the screen could show a secret, for example after the user pasted something.
- Use `adb -s <serial>` everywhere; other devices may be connected that are not yours.
- Undo what you changed (screen timeout, temporary files) and report anything you could not.
- Ask before doing anything that this procedure does not name.

### 1. Connect adb

On the phone: **Settings → Developer options → Wireless debugging**, switch it on, then **Pair device with pairing code**.
The user reads out the `IP:port` and the six-digit code; the code works once.

```bash
adb pair <ip>:<pairing-port> <code>
adb devices
```

- The phone should be listed, for instance as `adb-<serial>._adb-tls-connect._tcp`.
  `adb pair` once printed `protocol fault (couldn't read status message)` although it had worked, so run `adb devices` before you retry.
- If it is not listed or says `offline`, use the **IP address & Port** of the main Wireless debugging screen, which is a different port: `adb connect <ip>:<port>`.
- In WSL `adb start-server` can hang. Run `setsid nohup adb -L tcp:5037 server nodaemon > adb.log 2>&1 < /dev/null &` instead.

```bash
S=<serial from adb devices>
adb -s "$S" shell 'echo $(getprop ro.product.model) $(getprop ro.build.version.release) $(getprop ro.product.cpu.abi)'
```

The ABI must be `arm64-v8a`.

### 2. Install the apps

```bash
adb -s "$S" install -r MedTimer-full-release-signed.apk
```

Download the Termux APK from F-Droid and check it before installing.
`https://f-droid.org/api/v1/packages/com.termux` names the suggested `versionCode` (1002, which is 0.118.3, at the time of writing).

```bash
V=1002
curl -O https://f-droid.org/repo/com.termux_$V.apk
curl -o index-v1.json https://f-droid.org/repo/index-v1.json   # about 70 MB
python3 -I - <<EOF
import json, hashlib
index = json.load(open('index-v1.json'))
entry = next(p for p in index['packages']['com.termux'] if p['versionCode'] == $V)
actual = hashlib.sha256(open('com.termux_$V.apk', 'rb').read()).hexdigest()
print('sha256 matches F-Droid index:', actual == entry['hash'])
EOF
apksigner verify --print-certs com.termux_$V.apk | grep SHA-256   # must equal the "signer" in the index
adb -s "$S" install com.termux_$V.apk
```

The first installation attempt failed once with `INSTALL_FAILED_VERIFICATION_FAILURE` and worked on the second try.
`pm list packages` prints a `Shell does not have permission to access user 150` error on phones with a Secure Folder; add `--user 0`.

### 3. Permissions and battery

```bash
for p in POST_NOTIFICATIONS WRITE_EXTERNAL_STORAGE READ_EXTERNAL_STORAGE; do
  adb -s "$S" shell pm grant --user 0 com.termux android.permission.$p
done
for pkg in com.termux com.futsch1.medtimer; do
  adb -s "$S" shell cmd deviceidle whitelist +$pkg
  adb -s "$S" shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
done
```

The storage permission lets Termux write log files to `/sdcard/Download`, which is how the agent reads command output without screenshots.
Samsung's own "Unrestricted" battery setting cannot be set from adb; ask the user to choose it for Termux and medTimer.

The screen switches off after a minute and then Termux cannot be typed into.
Keep it on while you work, and put the old value back at the end:

```bash
adb -s "$S" shell settings get system screen_off_timeout      # remember this (usually 60000)
adb -s "$S" shell settings put system screen_off_timeout 900000
adb -s "$S" shell input keyevent KEYCODE_WAKEUP               # the user unlocks the phone
```

### 4. Type into Termux

Save this as `tp.sh`.
`input text` writes key events, so it ignores the keyboard layout and autocorrect.

```bash
#!/bin/bash
# usage: tp.sh '<one line>'   Types the line into Termux if Termux is in front and presses Enter.
S=<serial>
top=$(adb -s "$S" shell 'dumpsys activity activities' 2>/dev/null | tr -d '\r' \
      | grep -m1 topResumedActivity | grep -o -E 'com\.[a-zA-Z0-9_.]+/' | head -1)
[ "$top" = "com.termux/" ] || { echo "ABORT: foreground is ${top:-unknown}"; exit 1; }
txt=$(printf '%s' "$1" | sed 's/ /%s/g')
adb -s "$S" shell "input text '$txt'"
adb -s "$S" shell input keyevent 66
```

- In the typed text use no single quote and no `%`; use double quotes.
- Avoid `!` inside double quotes, because the Termux shell expands it as history.
- To stop a running command press Ctrl+C: `adb -s "$S" shell input keycombination 113 31`.
- To read the output, end the line with `2>&1 | tee /sdcard/Download/x.log` and then run `adb -s "$S" shell cat /sdcard/Download/x.log`.
  Delete these files at the end.
- `adb -s "$S" shell am start -n com.termux/.app.TermuxActivity` opens Termux, but Android may keep it behind another app; then ask the user to open it.

### 5. Run the set-up

Wait for the `~ $` prompt of the first start (it unpacks the system), then type these lines one by one.
Answer `N` to every `[default=N] ?` prompt in the log.

```bash
./tp.sh '( termux-wake-lock; pkg update -y && pkg upgrade -y && pkg install -y cloudflared ) 2>&1 | tee /sdcard/Download/setup.log'
./tp.sh 'clear; cloudflared tunnel login 2>&1 | tee /sdcard/Download/login.log'
```

Read the link from `login.log` and open it on the phone without printing it:

```bash
URL=$(adb -s "$S" shell cat /sdcard/Download/login.log | tr -d '\r' | grep -o -E 'https://dash\.cloudflare\.com/argotunnel\?[^ ]+' | head -1)
adb -s "$S" shell "am start -a android.intent.action.VIEW -d '$URL'"
```

Tell the user to sign in, choose the domain and tap **Authorize**.
When `login.log` says `You have successfully logged in`, open Termux again and continue (replace the names):

```bash
./tp.sh 'clear; cloudflared tunnel create medtimer-phone2 2>&1 | tee /sdcard/Download/create.log'
./tp.sh 'clear; cloudflared tunnel route dns medtimer-phone2 meds2.<your-domain> 2>&1 | tee /sdcard/Download/route.log'
```

`create.log` ends with `Created tunnel medtimer-phone2 with id <ID>`.
Put that ID in the next lines:

```bash
C='~/.cloudflared/config.yml'
./tp.sh "clear; echo \"tunnel: <ID>\" > $C; echo \"credentials-file: /data/data/com.termux/files/home/.cloudflared/<ID>.json\" >> $C; echo \"ingress:\" >> $C; echo \"  - hostname: meds2.<your-domain>\" >> $C; echo \"    service: http://127.0.0.1:8765\" >> $C; echo \"  - service: http_status:404\" >> $C"
./tp.sh 'echo "termux-wake-lock" > ~/start-tunnel.sh; echo "cloudflared tunnel --no-autoupdate run medtimer-phone2" >> ~/start-tunnel.sh'
./tp.sh 'rm -f ~/.cloudflared/cert.pem; nohup bash ~/start-tunnel.sh > /sdcard/Download/tunnel.log 2>&1 &'
adb -s "$S" shell 'grep -c "Registered tunnel connection" /sdcard/Download/tunnel.log'    # expect 4
```

From the laptop, `curl -si https://meds2.<your-domain>/mcp/wrong` should answer `404` with an empty body.
A new domain can take a few minutes before public resolvers know it.
This does not prove that the request reached the app, because the tunnel's fallback rule also answers `404`; the real test is the connector.

### 6. Hand over to the user

The agent must not see the secret, so the user does the rest in medTimer (the four settings in step 6 of the manual method) and adds the connector.
Then do the clean-up.

### Clean up

```bash
adb -s "$S" shell settings put system screen_off_timeout <the value you noted>
adb -s "$S" shell 'rm -f /sdcard/Download/setup.log /sdcard/Download/login.log /sdcard/Download/create.log /sdcard/Download/route.log /sdcard/Download/tunnel.log'
```

Then check that `~/.cloudflared` holds only `config.yml` and the `<id>.json` file, that `cloudflared` is running, and that no log files are left in `/sdcard/Download`.
The tunnel keeps writing to `/sdcard/Download/tunnel.log` until it restarts; for the long run use `~/tunnel.log` as in the manual method.
The user then switches **Wireless debugging** off and removes the computer under **Paired devices**.

## Add the connector

1. On claude.ai open **Customize → Connectors → Add custom connector**.
2. Enter the name, paste the connector URL, and under **Authentication** choose **No sign-in**.
3. Move the URL from the phone to the computer in a way that you delete afterwards, for example a message to yourself.
4. Ask Claude: "List my medicines", and then, for the banner on the screen: "Set `notification_priority` to `HIGH` for all of them".
   Claude asks you to approve each change.

Anthropic does not say that its mobile apps list connectors that were added on the web, so add the connector there and see whether your phone offers it.
Your plan has to allow custom connectors.

## Day to day

- **After a reboot** the tunnel does not start by itself.
  Open Termux and run `nohup bash ~/start-tunnel.sh > ~/tunnel.log 2>&1 &`.
  The MCP server itself comes back by itself.
  The Termux:Boot add-on could start the tunnel at boot; that was not tried.
- **Do not swipe Termux away** from the recent apps.
  `termux-wake-lock` keeps the phone's CPU awake, so expect some battery use.
- **Updating medTimer**: install the new APK with the same key over the old one; the server restarts by itself.
- **A leaked URL**: **Create new secret** in the MCP server settings and add the connector again.
- **Stop**: turn off the switch in the settings, or press Stop in the notification.
  Delete the tunnel with `cloudflared tunnel delete medtimer-phoneN` (this needs a fresh `cloudflared tunnel login`, since `cert.pem` was removed) and remove the DNS record in the Cloudflare dashboard.
- **Limit who can reach the host**: add a Cloudflare WAF rule that allows only Anthropic's address range, as described in [`mcp-server.md`](mcp-server.md#keep-it-safe).

## What was verified

On a real phone, by a coding agent over wireless adb, and the user confirmed that Claude then works with the phone:

- Installing the release APK over the existing app keeps the data and the server restarts.
- Termux 0.118.3 from F-Droid (SHA-256 and signer match the F-Droid index) installs, and `pkg install cloudflared` gives `cloudflared 2026.10.1`.
- `login`, `create`, `route dns`, the configuration and the start script, and four registered connections (Kyiv and Warsaw).
- The medTimer server answers through `https://meds1.<domain>` and Claude uses the tools.

Not verified: the paste-friendly `cat <<EOF` form in the manual method (the agent used the equivalent `echo` lines), Termux:Boot, long-term reliability under Doze with the screen off, and whether the mobile Claude apps show a connector added on the web.

## Tunnel options that did not work

| Option                                   | What happened                                                                                                                                         |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| ngrok, free plan                         | Works in Termux only through `termux-chroot` and `SSL_CERT_FILE=/etc/tls/cert.pem`, because its Go binary finds no DNS settings and no certificates on Android. The account had used up the monthly TCP-connection limit, and every request got `ERR_NGROK_729`. The free plan also has 1 GB and 20,000 requests a month and a single static domain, which is one phone. |
| ngrok's Java library inside the app      | Does not load on Android: `UnsatisfiedLinkError … NativeSession.connectNative`. The Android Gradle Plugin leaves the library out of the APK, and upstream tracks it as an [open issue](https://github.com/ngrok/ngrok-java/issues/50). |
| Cloudflare quick tunnel (`--url`)        | `api.trycloudflare.com` timed out from the phone and from the laptop. Cloudflare calls these tunnels a test service without an uptime guarantee.        |
| A copy of the data on a server           | The alarms and the data live on the phone, and the app can only restore a backup by replacing everything, so the server could only be read-only and stale. |

## Troubleshooting

| Symptom                                                | Likely cause and fix                                                                                                                       |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `404` with an empty body from the public URL           | A wrong path or an old secret, or the tunnel answered with its fallback rule. Copy the connector URL again.                                 |
| `403` from the public URL                              | The host in the URL differs from **Public tunnel address** in the settings.                                                                  |
| A Cloudflare error page (for example error 1033)       | The tunnel is not connected. Open Termux and start it.                                                                                       |
| `grep -c` prints less than `4`                         | The tunnel is still connecting, or the network blocks port 7844. Read `~/tunnel.log`.                                                       |
| `failed to request quick Tunnel … context deadline`    | You are using `--url` quick tunnels. Use a named tunnel as above.                                                                            |
| Termux does not take the typed text                    | The screen is off or locked, or another app is in front. Wake and unlock the phone and open Termux.                                           |
| `lookup … connection refused` or `certificate signed by unknown authority` | A Go program (not Termux's own `cloudflared`) without DNS settings and certificates. Run it as `SSL_CERT_FILE=/etc/tls/cert.pem termux-chroot <program>`, after `pkg install proot resolv-conf`. |
| `adb pair` fails with `protocol fault`                 | It may have worked already. Run `adb devices`. If the phone is not listed, pair again with a new code.                                       |
| The phone shows as `offline`                           | Connect to the port on the main Wireless debugging screen, not the pairing port, or switch Wireless debugging off and on.                     |
