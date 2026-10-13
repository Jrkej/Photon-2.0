# Testing PHOTON locally on an Android phone

How to run the backend on your computer, put the app on a phone over USB, and check that photos are shared, including when the app is in the background. Run every command from the project folder (`~/dev/Photon`). You'll use three terminals.

## One-time setup on the phone

1. Turn on USB debugging: Settings → About phone → tap **Build number** 7 times, then Settings → System → Developer options → **USB debugging**.
2. Plug the phone in, and on the "Allow USB debugging?" prompt tick **Always allow from this computer**, then tap **Allow**.

## Terminal 1: the backend (leave it running)

```bash
cd ~/dev/Photon
scripts/phone-backend.sh
```

It's ready when it prints `Uvicorn running on http://127.0.0.1:8080`. Ctrl-C stops it.

- The first run sets up the Python environment and builds Redis, which takes a few minutes.
- If it says a port is already in use, another backend is running. Stop it with `pkill -INT -f "bin/photon-dev"`.
- To try GitHub or Google sign-in, pass the credentials (setup in [prehost.md](prehost.md), section 5):
  ```bash
  PHOTON_GITHUB_CLIENT_ID=... PHOTON_GITHUB_CLIENT_SECRET=... scripts/phone-backend.sh
  PHOTON_GOOGLE_CLIENT_ID=<web client id> scripts/phone-backend.sh
  ```
  For local use, the GitHub OAuth App's callback URL is `http://127.0.0.1:8080/v1/auth/oauth/return`.

## Terminal 2: install the app on the phone

```bash
cd ~/dev/Photon
scripts/phone-install.sh
```

It forwards ports 8080 and 8081 to the phone over USB (`adb reverse`), then builds, installs and opens PHOTON.

| Command | When |
| --- | --- |
| `scripts/phone-install.sh` | First time, and after every code change |
| `scripts/phone-install.sh --reverse` | After replugging the cable or restarting the computer: restores the port forwarding, with no rebuild |
| `scripts/phone-install.sh --logs` | The same as the first, then follows the app's log |
| `ANDROID_SERIAL=<serial> scripts/phone-install.sh` | Several phones connected (serials from `adb devices`) |

## On the phone

1. Sign in: **Developer sign-in** with any name, or GitHub or Google if the backend has them configured.
2. Create a session and note the 8-character code.
3. Allow photos with **Allow all** (not "Select photos"), and allow notifications.
4. If a card asks to let PHOTON run in the background, tap it and allow.
5. A notification "PHOTON · sharing" stays visible while you're in a session. That's the sync service that watches for new photos.

## Terminal 3: a second member

The computer acts as another phone:

```bash
cd ~/dev/Photon
.venv/bin/photon-cli --profile bob login
.venv/bin/photon-cli --profile bob join <CODE>
.venv/bin/photon-cli --profile bob listen --save-dir ./received
```

Leave `listen` running: every photo the phone shares is printed and saved to `./received/`. A second phone works too; install the app on it and join with the code.

## What to test

| Test | How | Expected |
| --- | --- | --- |
| App open | Take a photo while PHOTON is on screen | A line in terminal 3 and a file in `./received/` within a few seconds |
| Background | Press Home, open the camera, take a photo | The same, within a few seconds |
| App swiped away | Swipe PHOTON from recents, then take a photo | The same; the sharing notification stays or comes back |
| Phone receives | In a fourth terminal: `.venv/bin/photon-cli --profile bob upload some.jpg` | It appears on the phone, and in its gallery under **Pictures/PHOTON** |
| Recall | Recall a shared photo in the app | Terminal 3 prints `PHOTO_RECALLED` |
| Leave | Tap Leave in the app | Terminal 3 prints the member leaving; sharing stops |

Other `photon-cli` commands: `info`, `snapshot`, `catchup`, `recall <sha>`, `download <sha>`, `leave`, `end`.

## If something doesn't arrive

```bash
scripts/phone-diagnose.sh            # read-only report: service, battery, jobs, why the app died, app log
scripts/phone-diagnose.sh --watch    # then follows the app's log live while you take a photo
```

The usual causes, in order:

1. **The backend in terminal 1 has stopped.** Start it again.
2. **The port forwarding is gone** after the cable was replugged or adb restarted. The app's log shows `Failed to connect to /127.0.0.1:8080`. Run `scripts/phone-install.sh --reverse`.
3. **The phone shows "unauthorized"** in the report. Unlock it and accept the USB debugging prompt.
4. **"Sync service: NOT RUNNING"** while you're in a session. Open the app once; it restarts the service. If it keeps stopping, the report's "Why the process last died" and app log sections show why.

## Starting over

- Reset all local data (accounts, sessions, photos, keys): stop the backend and delete `.devdata/`.
- Remove the app from the phone: `adb uninstall com.photon.app`.
