# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub's
**Security > Report a vulnerability** form on this repository. Do not open a
public issue for them. Expect a first reply within about a week.

## Scope and expectations

The app is a WebView wrapper for a Harmony Hub running harmony-hub-control on
a trusted local network. It talks to the hub over plain HTTP by design, so
anything on the same network can observe that traffic. Reports about the app's
handling of the hub address, WebView settings, signing or release pipeline are
welcome.

Releases are signed with a key held only in this repository's CI secrets. Only
APKs published on this repository's Releases page are official.
