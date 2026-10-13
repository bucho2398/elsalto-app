# Security policy

## Supported versions

Only the latest official build, the APK on the
[`download`](https://github.com/bucho2398/elsalto-app/tree/download) branch, gets fixes. New
versions install over the old one, so updating is the fix. Unofficial *El Salto FOSS* builds and
forks are their builders' responsibility.

## Reporting a vulnerability

Please don't open a public issue. Report it privately with
[*Report a vulnerability*](https://github.com/bucho2398/elsalto-app/security/advisories/new) on the
Security tab. Say what's affected, how to reproduce it and what an attacker could do with it.

This is a one-person hobby project, so there's no bounty and no fixed timeline, but reports are
read and acknowledged, usually within a week, and fixed in the next official build. You'll be
credited in the advisory unless you'd rather not be.

What's in scope: the app's code here, how it downloads and checks wall data (`Data.kt`: the
`index.json` checksums and the atomic swap), and the official APK. Problems in Open-Meteo,
RainViewer, NASA GIBS, Esri or GitHub Pages themselves go to those services.
