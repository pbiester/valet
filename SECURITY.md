# Security

Valet handles brokered database credentials, so this document matters more than usual.

## How credentials are handled

- **Valet performs zero authentication of its own.** It shells out to the `boundary` CLI,
  which reuses your existing keyring auth token. Valet never reads, stores, prompts for, or
  transmits your Boundary credentials. It has no config file and no credential store.
- **Brokered database credentials live only in memory, for as long as a connection needs
  them.** They are read from the `boundary connect -format=json` output, passed to the
  PostgreSQL driver as connection properties, and never written to disk.
- **Secrets are redacted everywhere they could surface.** The `password`,
  `secret.decoded.password`, and base64 `secret.raw` fields never appear in `toString()`,
  logs, or exception messages. The raw secret blob is not even retained in Valet's model.
- **The delegate connection is loopback only.** Valet connects to `127.0.0.1:<port>` on the
  local Boundary proxy — never to a remote host directly.
- **Subprocesses are cleaned up.** Sessions are reaped when idle, and a JVM shutdown hook
  kills any remaining `boundary connect` proxies so an orphaned process can't outlive the
  client. As a backstop, Valet sets the CLI's `-inactive-timeout` just above its own idle
  timeout so Boundary itself reaps a proxy left behind by a crashed JVM.

## What Valet does *not* protect against

- The security of your Boundary deployment, targets, credential stores, and RBAC — those
  are yours to configure.
- Anything the `boundary` CLI or PostgreSQL driver does with the credentials once handed
  over.
- A local attacker who can already read this JVM's memory or attach a debugger.

## Reporting a vulnerability

Please report suspected vulnerabilities privately via
[GitHub Security Advisories](https://github.com/pbiester/valet/security/advisories/new)
rather than a public issue. Include the version, a description, and a reproduction if you
have one. You'll get an acknowledgement within a few days.

Please do not include real credentials, session IDs, or controller addresses in reports —
scrub them to placeholders first.
