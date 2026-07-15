# Valet

**A JDBC driver that tunnels PostgreSQL connections through [HashiCorp Boundary](https://www.boundaryproject.io/).**

> A valet key starts the car but won't open the trunk. Valet hands your database client a
> brokered credential and a local port, and nothing else.

You configure **one JDBC URL**. On connect, Valet starts a Boundary session, reads the
brokered PostgreSQL credentials and the local proxy port, opens a real PostgreSQL
connection through that port, and hands it back. You never see a port, a password, or a
session.

```
jdbc:boundary://<scope-name>/<target-name>/<database>
```

> **Not affiliated with or endorsed by HashiCorp.** "Boundary" is a trademark of
> HashiCorp; this is an independent open-source project that shells out to the public
> `boundary` CLI.

---

## Requirements

Valet **shells out to the `boundary` CLI** — it does not re-implement the Boundary proxy
protocol. That means:

- **The `boundary` CLI must be installed and on your machine.** This is a hard runtime
  dependency. ([Install it here.](https://developer.hashicorp.com/boundary/install))
- **You must already be authenticated.** Valet reuses your existing keyring auth token
  (`boundary authenticate …`); it never handles your Boundary credentials itself.
- Java 17 or newer.

Supported `boundary` CLI versions: **0.13+** (any version whose `boundary connect
-format=json` emits the session/credentials shape described below). Tested against
`boundary dev`.

---

## Install

Download `valet-bundle-<version>.jar` from the
[latest release](https://github.com/pbiester/valet/releases). It is a single fat JAR that
includes the PostgreSQL driver and a shaded copy of Jackson — nothing else to add. Point
your GUI client (JetBrains IDEs, DBeaver) or any JDBC classpath at it.

> ⚠️ The bundle carries its own copy of PGJDBC. **Do not put it on a classpath that already
> has `org.postgresql:postgresql`** — they will clash. The bundle is meant to be loaded as a
> standalone driver JAR (which is exactly how GUI database clients load drivers).

Valet is distributed only as this bundle JAR; it is not published to Maven Central.

---

## Setup in your JetBrains IDE

**These steps are identical in every JetBrains IDE** — DataGrip, IntelliJ IDEA Ultimate,
GoLand, PyCharm Professional, PhpStorm, RubyMine, Rider — because they all share the same
bundled *Database Tools and SQL* plugin. (A Go team in GoLand gets this working even
though nothing else in their stack touches the JVM.)

1. **Add the driver.** Open the *Database* tool window → the **`+`** menu →
   **Driver**. Name it `Boundary`.
2. **Point it at the bundle JAR.** In the driver's **Driver Files** section, click **`+`**
   → **Custom JARs…** and select the `valet-bundle-<version>.jar` you downloaded.
3. **Set the driver class.** In **Class**, choose `dev.isonet.valet.ValetDriver` from the
   dropdown (it appears once the JAR is added).
4. **Set the URL template.** In **Expert options** / **URL templates**, add:

   ```
   jdbc:boundary://{scope}/{target}/{database}[?<&,user=&password=]
   ```

   Or simply paste a full URL when creating the data source.
5. **Create a data source** from the `Boundary` driver, set the **URL** to
   `jdbc:boundary://<scope-name>/<target-name>/<database>`, leave **User** and **Password**
   blank (Valet injects the brokered credentials), and **Test Connection**.

> Screenshots: `docs/jetbrains-add-driver.png`, `docs/jetbrains-datasource.png`
> *(placeholders — add before release)*

### DBeaver

**Database** → **Driver Manager** → **New**. Add the bundle JAR under **Libraries**, set
**Class Name** to `dev.isonet.valet.ValetDriver`, set the **URL Template** to
`jdbc:boundary://{host}/{database}`, then create a connection with a full
`jdbc:boundary://…` URL. Leave username/password blank.

---

## Finding your scope name

Valet resolves targets by **scope name**, not scope ID. Look yours up once:

```bash
boundary scopes read -id=p_1234567890 -format=json | jq -r .item.name
```

(Boundary scope names are only unique within their parent scope. If two orgs own
same-named projects you may hit ambiguity — that surfaces as whatever error the CLI emits.)

---

## URL reference

```
jdbc:boundary://<scope-name>/<target-name>/<database>[?params]
```

Exactly three path segments, **all required and all non-empty**. Each is percent-decoded
individually, so a name containing `/` works as `%2F`. A trailing slash is an error, not a
blank database.

| Segment | Maps to |
|---|---|
| `<scope-name>` | Boundary `-target-scope-name` |
| `<target-name>` | Boundary `-target-name` |
| `<database>` | the PostgreSQL database to open |

Any parameter **not** prefixed `boundary.` is passed straight through to PGJDBC
(`?ssl=true&ApplicationName=reports`). Valet-specific parameters:

| Parameter | Default | Purpose |
|---|---|---|
| `boundary.addr` | `$BOUNDARY_ADDR` | Controller URL |
| `boundary.cli-path` | auto-discovery | Absolute path to the `boundary` binary |
| `boundary.connect-timeout` | `30s` | How long to wait for the session |
| `boundary.idle-timeout` | `60s` | Grace before an unused session is torn down |
| `boundary.credential-name` | — | Pick one when a target brokers several credentials |
| `boundary.brokered-credentials` | `true` | `false` = use the caller's user/password instead |
| `boundary.target-id` | — | Resolve the target by `ttcp_…` id instead of scope/target name (for deployments where name resolution is ambiguous). The scope/target segments are then ignored. |

### CLI discovery

A JetBrains IDE launched from the Dock/Finder/Start Menu **does not inherit your shell
`PATH`**, so a Homebrew-installed `boundary` can be invisible. Valet searches, in order:
`boundary.cli-path` → `$VALET_BOUNDARY_CLI` → `PATH` → `/opt/homebrew/bin`,
`/usr/local/bin`, `/usr/bin`, `~/.local/bin` → `C:\Program Files\Boundary`. If none work,
the error lists every path it tried. Set `boundary.cli-path` or `$VALET_BOUNDARY_CLI` to
override.

---

## Limitations

| Limitation | Detail |
|---|---|
| `boundary` CLI required at runtime | Valet shells out to it; install it and authenticate first. |
| Session duration caps connections | Boundary enforces a server-side max session lifetime (commonly ~8h); the shared session is retired near expiry. |
| Brokered credentials only | Injected credentials are a Boundary Enterprise, SSH-only feature and are not supported. |
| PostgreSQL only | No MySQL or other delegates. |
| Bundle carries its own PGJDBC | Unsafe on a classpath that already has `org.postgresql:postgresql`. Load it as a standalone driver JAR (the normal case for GUI clients). |

---

## How it works

1. `connect()` parses the URL and starts (or reuses) a Boundary session for the target by
   running `boundary connect -format=json` with an explicit free `-listen-port`.
2. It reads the local proxy port and the brokered `username_password` credential from the
   session JSON.
3. It opens a real `jdbc:postgresql://127.0.0.1:<port>/<database>` connection with those
   credentials and hands back a thin wrapper.
4. Sessions are **ref-counted and shared** across concurrent connections to the same
   target, and torn down after an idle grace period. A JVM shutdown hook kills any
   remaining proxy subprocesses (covers Flyway, CI harnesses, test JVMs).

See [`docs/`](docs/) and the plan for the gory details (streaming JSON reads, stdout
draining, bind-race retries, credential redaction).

---

## Building

```bash
./gradlew build            # compile + unit tests (all modules)
./gradlew :bundle:shadowJar # produce the fat bundle jar
./gradlew integrationTest   # boundary dev + Testcontainers (needs Docker + auth)
```

Java 17, Gradle wrapper included. The integration tests **download their own `boundary`
CLI** (checksum-verified, cached under `.boundary/`) — no manual install. They additionally
need a Docker daemon and an authenticated Boundary session (`BOUNDARY_ADDR` plus a
`BOUNDARY_TOKEN` the spawned CLI can inherit); they self-skip otherwise. CI runs them on
Linux only, standing up `boundary dev` itself.

## Licence

[Apache-2.0](LICENSE). See [SECURITY.md](SECURITY.md) for how credentials are handled and
how to report a vulnerability.
