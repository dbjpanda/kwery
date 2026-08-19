# Security policy

## Reporting a vulnerability

**Please do not open a public issue.**

Use GitHub's private vulnerability reporting, which is enabled on this
repository:
[Report a vulnerability](https://github.com/dbjpanda/kwery/security/advisories/new).

You should get an acknowledgement within a few days. Kwery is maintained by one
person, so please allow reasonable time before disclosing publicly.

## What is in scope

Kwery caches server responses and persists them to disk, so the interesting
surface is data handling rather than networking:

- The persisted cache and the offline mutation queue, in `kwery-persist` and
  `kwery-persist-room`. Both write to app-private storage.
- Deserialization of a persisted snapshot written by an earlier version.
- Query keys, which are serialized into cache identifiers.

Kwery does not open network connections. It calls the function you give it, so
anything about TLS, certificates or HTTP belongs to your client, whether that is
Retrofit, Ktor or OkHttp.

## Supported versions

Kwery is pre-1.0. Fixes land on the latest released version only.
