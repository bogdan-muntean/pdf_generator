# ADR-0021: Desktop launcher: shortcut, automatic port, single instance, clean shutdown

- Status: Accepted (owner request; questions Q21–Q25 answered by the lead architect)

## Request
"Make a shortcut to start the app. It must find free ports by itself, and when I close it, it must release its ports before the app stops."

## Decisions

| # | Question | Decision | Alternatives rejected |
|---|----------|----------|-----------------------|
| Q21 | Port | `pdfgen.preferred-port` (8080) if free on 127.0.0.1, otherwise port 0 (OS-assigned). One retry with port 0 if Tomcat still hits "port in use" (race). The app opens the browser at the real URL. | Range scan 8080..8099: more code, same race. Always port 0: the URL changes every run. |
| Q22 | Start / close UX | Shortcut runs `javaw` (no console). Tray icon with *Open viewer* / *Quit*, plus a **Quit app** button in the viewer. `start.bat` stays as the console launcher (Ctrl+C runs the same shutdown). Logs go to `logs/pdf-generator.log`; a startup failure shows a dialog. | Console only (clumsy); tray only (no easy diagnostics). |
| Q23 | Single instance | OS file lock on `run/instance.lock` for the whole process (released by the OS even on a crash). `run/instance.properties` holds port, pid and a random id. A second launch that cannot get the lock waits for that file, checks `GET /api/ping` (same id), opens the browser at that port and exits. | No guard: a double click starts two servers on different ports. |
| Q24 | Shutdown order and security | Quit closes the Spring context on its own thread. **The web server stops first** (`server.shutdown=graceful`, at most 5 s), which releases the port. Then beans are destroyed: tray icon removed, instance file deleted, lock released. Then `System.exit`. `POST /api/shutdown` needs the `X-Pdfgen-Action: shutdown` header; a custom header forces a CORS preflight that this app never approves. All requests with a `Host` other than 127.0.0.1/localhost are rejected (DNS-rebinding guard). | Unprotected endpoint: any web page could close the app. |
| Q25 | Shortcut | `create-shortcut.ps1` builds the jar and creates `PDF Generator.lnk` on the Desktop (Start Menu with `-StartMenu`), using the committed `app.ico`. Idempotent. | jpackage/MSI (ADR-0012); a shortcut made by hand (not reproducible). |

Implementation note: the instance file is a `.properties` file rather than JSON. It holds three values and needs no JSON library before Spring starts.

## Fixes found in use (2026-11-05)
- **Rebuilding the jar under a running app broke it.** `mvn verify` rewrote `target/pdf-generator.jar` while the app
  started from the shortcut was running from it. On Quit, class loading failed in the shutdown thread, so Tomcat kept the
  port and the process never exited.
  - Fix 1: `create-shortcut.ps1` installs a **copy** in `app/` and the shortcut runs that copy. Before replacing it,
    the script asks the running app to quit.
  - Fix 2: `AppShutdown` catches every error and always calls `System.exit`, and a 20 s watchdog halts the JVM if a clean exit hangs.

## Consequences
- Tests: the port is bindable again after Quit, the lock and instance file are released, shutdown without the header gives 403, a foreign `Host` gives 403. The launcher was also verified by hand: with 8080 busy the app picked another port, and a second launch handed over and exited.
- After changing the code, rerun `create-shortcut.ps1`: it rebuilds, closes the running app (after asking) and installs the new copy in `app/`.
