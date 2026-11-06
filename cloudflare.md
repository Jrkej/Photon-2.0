# Running PHOTON on this machine, reachable through Cloudflare Tunnel

The backend runs here on localhost, as in [local.md](local.md). Cloudflare Tunnel gives it public `https://` addresses, so phones can use it from any network: mobile data, another Wi-Fi, a friend's phone across town. There's no port forwarding, no public IP and no certificate to set up.

```
phone ──https──▶ Cloudflare edge ──tunnel──▶ cloudflared (this machine) ──▶ 127.0.0.1:8080  API (Auth + orchestrator, WebSocket)
                                                                        └─▶ 127.0.0.1:8081  image server
```

`cloudflared` makes an outbound connection to Cloudflare, and requests come back through it. The API and the image server each need their own public hostname, because the server tells phones where to upload (`upload_url`) and the app is built with both addresses.

**Contents:** [1. Choose a tunnel](#1-choose-a-tunnel) · [2. Sign-in once it's public](#2-sign-in-once-its-public) · [3. Quick tunnels](#3-quick-tunnels-no-account) · [4. A named tunnel on your domain](#4-a-named-tunnel-on-your-own-domain) · [5. Phones and the CLI](#5-phones-and-the-cli) · [6. Cloudflare details](#6-cloudflare-details-that-matter) · [7. What Cloudflare can see](#7-what-cloudflare-can-see) · [8. Troubleshooting](#8-troubleshooting) · [9. Stopping](#9-stopping-and-cleaning-up)

## 1. Choose a tunnel

| | Quick tunnel | Named tunnel |
| --- | --- | --- |
| Needs | Nothing: no account, no domain | A Cloudflare account and a domain on Cloudflare |
| Addresses | Random `https://<words>.trycloudflare.com`, new on every `cloudflared` start | Your own, fixed (`photon-api.example.com`) |
| After a restart | Restart the backend with the new URLs, and rebuild and reinstall the app | Nothing to change |
| Limits | 200 requests in flight per tunnel; meant for testing. Cloudflare doesn't document WebSocket support for quick tunnels; if the live feed won't connect, use a named tunnel. | Your plan's limits |
| Good for | Trying it once, today | Testing over days, with real sign-in |

Both need `cloudflared`. This machine already has it (`cloudflared --version`); elsewhere, see Cloudflare's [downloads page](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/).

Set up the backend once as in [local.md](local.md) (the first `scripts/phone-backend.sh` run builds everything), and stop any backend that's running: `pkill -INT -f "bin/photon-dev"`.

## 2. Sign-in once it's public

The dev backend has **Developer sign-in**, which takes any username without a password. On a public address, anyone who has the URL can sign in as anyone. `photon-dev --dev-login` chooses how far it reaches:

| `--dev-login` | Who can use Developer sign-in | Use it when |
| --- | --- | --- |
| `on` (the default) | Everyone, through the tunnel too | A short test with a quick tunnel's random URL, stopped afterwards |
| `local` | Only requests made on this machine directly, such as `photon-cli --api http://127.0.0.1:8080`. Anything that comes through Cloudflare is refused, and the app hides the button. | A named tunnel that stays up: phones sign in with GitHub or Google |
| `off` | Nobody | You don't need the CLI |

`local` works because Cloudflare adds `X-Forwarded-For` and `CF-Connecting-IP` to every request it forwards, and a visitor can't remove them. A request carrying either header is never treated as local.

With `local` or `off`, phones need a real provider. GitHub is the easiest:

1. Create a GitHub OAuth App ([prehost.md](prehost.md#5-sign-in-providers-google-github) has the steps) with the callback URL `https://<your API hostname>/v1/auth/oauth/return`, for example `https://photon-api.example.com/v1/auth/oauth/return`.
2. Pass its id and secret when you start the backend: `PHOTON_GITHUB_CLIENT_ID=... PHOTON_GITHUB_CLIENT_SECRET=... scripts/phone-backend.sh ...`.

A quick tunnel's hostname changes on every start, so GitHub sign-in with one means editing the callback each time. Use Developer sign-in there.

Google sign-in works as it does locally (`PHOTON_GOOGLE_CLIENT_ID=<web client id>`); see prehost.md section 5.

## 3. Quick tunnels (no account)

Four terminals, all in `~/dev/Photon`.

**Terminal 1: the API tunnel.**

```bash
cloudflared tunnel --url http://127.0.0.1:8080
```

It prints a box with an address like `https://quiet-river-maple-lamp.trycloudflare.com`. That's your **API URL**. Leave it running.

**Terminal 2: the image server tunnel.**

```bash
cloudflared tunnel --url http://127.0.0.1:8081
```

A second address: your **image URL**.

**Terminal 3: the backend,** told its public addresses:

```bash
scripts/phone-backend.sh --api-url https://quiet-river-maple-lamp.trycloudflare.com \
                         --image-url https://bright-stone-oak-field.trycloudflare.com
```

It prints `PHOTON dev stack: API https://... images https://... dev login on`, then `Uvicorn running on http://127.0.0.1:8080`. Use `127.0.0.1`, not `localhost`, in the `cloudflared` commands. The backend listens on IPv4 only, and `localhost` can resolve to `::1` first.

Check both tunnels from a browser or another machine:

```bash
curl https://quiet-river-maple-lamp.trycloudflare.com/healthz    # ok
curl https://bright-stone-oak-field.trycloudflare.com/healthz    # ok
```

**Terminal 4: the app,** built for those URLs (see [section 5](#5-phones-and-the-cli)):

```bash
scripts/phone-install.sh --urls https://quiet-river-maple-lamp.trycloudflare.com \
                                https://bright-stone-oak-field.trycloudflare.com
```

If you restart a quick tunnel, its address changes. Restart the backend with the new URL, and run `phone-install.sh --urls ...` again, because the app keeps the address it was built with.

## 4. A named tunnel on your own domain

You need a domain whose DNS is on Cloudflare (any plan, Free included). The examples use `example.com`, `photon-api.example.com` and `photon-img.example.com`. Keep the hostnames one level below your domain. Cloudflare's free certificate covers `*.example.com` but not `*.photon.example.com`.

**Once:**

```bash
cloudflared tunnel login                 # opens a browser: pick the domain; saves ~/.cloudflared/cert.pem
cloudflared tunnel create photon         # prints the tunnel's id and writes ~/.cloudflared/<id>.json
cloudflared tunnel route dns photon photon-api.example.com
cloudflared tunnel route dns photon photon-img.example.com
```

`route dns` adds a CNAME record from each hostname to the tunnel.

Then write `~/.cloudflared/photon.yml`, with your tunnel id and home directory:

```yaml
tunnel: <tunnel id>
credentials-file: /home/<you>/.cloudflared/<tunnel id>.json

ingress:
  # Like the production proxy: service-to-service and metrics routes are not public.
  - hostname: photon-api.example.com
    path: ^/(internal/|metrics)
    service: http_status:404
  - hostname: photon-api.example.com
    service: http://127.0.0.1:8080
  - hostname: photon-img.example.com
    path: ^/(internal/|metrics)
    service: http_status:404
  - hostname: photon-img.example.com
    service: http://127.0.0.1:8081
  - service: http_status:404          # required last rule: anything else
```

Check it:

```bash
cloudflared tunnel --config ~/.cloudflared/photon.yml ingress validate
cloudflared tunnel --config ~/.cloudflared/photon.yml ingress rule https://photon-api.example.com/v1/ws
```

The second command should print the rule with `service: http://127.0.0.1:8080`.

The `/internal/` routes need service tokens anyway, but there's no reason to offer them. A separate file instead of `config.yml` keeps this tunnel's settings away from other `cloudflared` commands, such as quick tunnels.

**Every time,** in three terminals:

```bash
# 1. the tunnel
cloudflared tunnel --config ~/.cloudflared/photon.yml run photon

# 2. the backend
PHOTON_GITHUB_CLIENT_ID=... PHOTON_GITHUB_CLIENT_SECRET=... \
  scripts/phone-backend.sh --api-url https://photon-api.example.com \
                           --image-url https://photon-img.example.com --dev-login local

# 3. the app, the first time and after app changes
scripts/phone-install.sh --urls https://photon-api.example.com https://photon-img.example.com
```

The hostnames don't change, so one build of the app keeps working. Phones you've installed it on use it from anywhere.

To keep the tunnel running without a terminal, see `cloudflared service install` in Cloudflare's docs. Only do this if you want the backend reachable whenever this machine is on.

## 5. Phones and the CLI

**Phones.**

```bash
scripts/phone-install.sh --urls <API URL> <image URL>          # one phone on USB
scripts/phone-install.sh --urls <API URL> <image URL> --all    # every phone on USB
```

`phone-install.sh --urls` builds the debug app for those URLs. It installs and opens the app on the phones plugged in, and prints the APK path. To install on a phone that isn't plugged in, copy the APK to it and open it (allow "Install unknown apps"). There's no USB forwarding, so after installing you can unplug, turn Wi-Fi off and use mobile data.

**Every phone in a session needs a build with the same URLs.** A phone built by plain `phone-install.sh` (USB) or `--lan` talks to another address.

Then, on each phone: sign in (Developer sign-in, or GitHub/Google), create a session on one and join by code on the others. A newcomer sees "Waiting for a member to share the key" until another member's app is open and seals the session key for them; that takes a moment ([e2ee.md](e2ee.md#53-what-every-client-does-key-maintenance)).

**The CLI on this machine.** Point it at localhost, which skips Cloudflare and works with every `--dev-login` setting:

```bash
CLI=".venv/bin/photon-cli --api http://127.0.0.1:8080 --image http://127.0.0.1:8081"
$CLI --profile bob login
$CLI --profile bob join <CODE>                    # waits up to 15 s for a member to share the key
$CLI --profile bob listen --save-dir ./received   # decrypts each photo and saves the original
```

Uploads from this CLI still go out through the image tunnel and back, because the server names its public `upload_url`. That works; it's just a round trip through Cloudflare.

**The CLI on another computer** uses the public URLs, and needs `--dev-login on`:

```bash
photon-cli --api https://<API URL> --image https://<image URL> --profile carol login
```

## 6. Cloudflare details that matter

| Topic | What happens | What to do |
| --- | --- | --- |
| WebSocket (`/v1/ws`) | Cloudflare carries it, and drops connections that are idle for about 100 seconds. PHOTON's clients ping every 30 seconds. | Nothing |
| Upload size | Cloudflare refuses request bodies over 100 MB on the Free plan. PHOTON sends photos (25 MB at most) in 1 MB chunks. | Nothing |
| Response time | Cloudflare gives up on an origin after 100 seconds (error 524). `complete` answers within about 5 seconds, or says 202 and the app asks again. | Nothing |
| Caching | Downloads are `Cache-Control: private, no-store`, and API paths have no file extensions Cloudflare would cache by default | Don't add cache rules for these hostnames |
| Client addresses | Cloudflare appends the visitor's address to `X-Forwarded-For`. The backend trusts that header only from `127.0.0.1` (where `cloudflared` connects from), so per-address limits, such as joins per minute, count each phone separately. | Nothing |
| Bot protection | **Bot Fight Mode** and **Browser Integrity Check** may answer the app's or the CLI's requests with a challenge page, which only a browser can pass | If requests fail with a 403 or 503 HTML page, turn Bot Fight Mode off (Security → Bots), or add a configuration rule for the two hostnames that turns off Browser Integrity Check |
| Cloudflare Access | It would put a login page in front of the hostnames, which the apps can't get past | Don't protect these hostnames with Access |

## 7. What Cloudflare can see

Cloudflare's edge terminates TLS, so it can read requests as the PHOTON server can: access tokens, session ids, who is in which session, upload sizes and times, members' public keys and the sealed key boxes.

It can't see photos. They're encrypted on the phone before upload, and neither the server nor Cloudflare holds a key that opens them ([e2ee.md §1](e2ee.md#1-what-it-protects-and-from-whom)). An access token seen in transit is valid for at most 15 minutes. This is a development setup; for real users, use the production deployment in [prehost.md](prehost.md).

## 8. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `curl https://<URL>/healthz` gives error 1033 or 530 | `cloudflared` isn't connected. Start it (terminal 1, or 1 and 2), and check its log for `Registered tunnel connection`. |
| Error 502 | The tunnel is up but nothing answers on `127.0.0.1:8080` or `:8081`. Start the backend; use `127.0.0.1` in the `cloudflared` command or `photon.yml`, not `localhost`. |
| The app says it can't reach the server | It was built for other URLs (an old quick tunnel, USB or `--lan`). Run `phone-install.sh --urls ...` again with the current ones. |
| Photos stay at "uploading"; the backend log shows uploads never arrive | The backend was started without `--image-url`, so it tells phones to upload to `127.0.0.1:8081`, the phone itself. Restart it with both `--api-url` and `--image-url`. |
| No Developer sign-in button on the phone | The backend runs with `--dev-login local` or `off`. Use GitHub or Google, or restart with `--dev-login on`. |
| GitHub sign-in fails after returning from the browser | The OAuth App's callback URL isn't exactly `https://<API hostname>/v1/auth/oauth/return`. |
| Requests fail with a 403 or 503 HTML page | Cloudflare's bot protection ([section 6](#6-cloudflare-details-that-matter)). |
| Live updates stop and start | A quick tunnel's WebSocket, or a restart of `cloudflared` (which drops open WebSockets). Apps reconnect and catch up. If it keeps happening, use a named tunnel. |
| A member stays at "Waiting for the session key" | No other member's app has been open since they joined. Open PHOTON on another member's phone, or run `$CLI --profile <member> keys` for a CLI member. |
| `429` on many requests | A quick tunnel's 200-request limit, or PHOTON's own limits. For load tests, raise them, for example `PHOTON_JOIN_PER_MINUTE=1000`. |

## 9. Stopping and cleaning up

- Ctrl-C in each terminal stops the backend and the tunnels. A quick tunnel's address then stops working for good.
- To remove a named tunnel: `cloudflared tunnel delete photon`, then delete the two CNAME records in the Cloudflare dashboard (DNS → Records), and `~/.cloudflared/photon.yml` and `<tunnel id>.json`.
- The backend's data (accounts, sessions, encrypted files) is in `.devdata/`; [local.md](local.md#starting-over) explains how to reset it.
