# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Report privately through GitHub's private vulnerability reporting:
https://github.com/lunar-cats/kimia-ledger/security/advisories/new

Include: affected version or commit, a description, reproduction steps or a proof of concept, and the impact
you believe it has (for example money creation, cross-tenant read, history tampering).

## What to expect

| Step | Target |
|---|---|
| Acknowledgement | within 3 business days |
| Triage and severity | within 7 days |
| Fix or mitigation for critical/high | within 30 days |
| Public advisory and credit | with the fixing release, if you wish to be named |

We use CVSS for severity. Anything that lets a caller create or destroy money, read or write another tenant's
data, or alter journal history is treated as critical.

## Supported versions

Pre-1.0: only the latest release receives fixes. From 1.0: the latest minor of the current major.

## Scope

In scope: this repository's code, published contracts, container images and release artifacts.
Out of scope: third-party dependencies (report upstream, tell us too), deployments operated by others,
denial of service by volume alone.

## Safe harbour

Good-faith research that respects privacy, avoids data destruction and does not target systems you do not
own will not be met with legal action from the maintainers.
