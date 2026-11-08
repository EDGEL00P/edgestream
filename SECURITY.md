# Security policy

EdgeStream handles provider logins, API keys and a local-network phone link, so security issues
are taken seriously.

## Reporting

Do not open a public issue for a vulnerability. Report it privately through GitHub's
**Security › Report a vulnerability** on this repository, with steps to reproduce and the
version affected (Settings › Remote & About on the TV).

## Scope

- Encryption at rest (SQLCipher database, Keystore-sealed settings)
- The phone remote server and its pairing, encryption and replay protection
- TV-to-TV setup transfer
- Handling of provider credentials and third-party API keys
