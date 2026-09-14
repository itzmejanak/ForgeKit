# Security Policy

## Supported versions

Security fixes are released for the latest published version of ForgeKit. Please update to the
[latest release](https://github.com/itzmejanak/ForgeKit/releases/latest) before reporting.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Report privately through GitHub:
[**Report a vulnerability**](https://github.com/itzmejanak/ForgeKit/security/advisories/new)
(repository **Security** tab → **Advisories** → **Report a vulnerability**).

Include as much as you can:

- affected version and device / Android version
- steps to reproduce, or a minimal `.forge` package that demonstrates the issue
- the impact you observed (for example: signature bypass, permission escalation, reading another
  plugin's data, escaping the app sandbox)

You can expect an acknowledgement within 7 days. We will keep you updated while a fix is
prepared, and credit you in the advisory unless you prefer otherwise.

## Scope

In scope: the ForgeKit app, its package import and signature verification, the permission and
trust model, the job protocol, the runtime bridge and the command-line tools in this repository.

Out of scope: vulnerabilities in upstream Termux packages themselves (report those to the
[Termux project](https://github.com/termux/termux-packages)), and issues that require an
already-compromised or rooted device.

The security design is documented in [`Docs/SECURITY.md`](Docs/SECURITY.md).
