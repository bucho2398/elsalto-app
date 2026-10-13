# Contributing

Thanks for helping. Issues and pull requests go to
[bucho2398/elsalto-app](https://github.com/bucho2398/elsalto-app). Everyone taking part follows the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Report a bug or ask for a feature** with the [issue forms](https://github.com/bucho2398/elsalto-app/issues/new/choose).
  Search first, and include the app version and your phone. A wetness estimate that was wrong at the
  crag is a useful bug: say which wall, when, and how the rock really was.
- **Ask questions or share ideas** in [Discussions](https://github.com/bucho2398/elsalto-app/discussions).
- **Fix walls and routes** in [elsalto-data](https://github.com/bucho2398/elsalto-data).
- **Send code or docs.** New here? Look for issues labelled
  [good first issue](https://github.com/bucho2398/elsalto-app/labels/good%20first%20issue). For
  anything bigger than a small fix, open an issue first so we can agree on the approach.
- **Security problems** go privately, see [SECURITY.md](SECURITY.md).

## Pull requests

- Keep pull requests small and say what they fix or add. CI must pass: it runs the model test and
  builds a debug APK you can try (download it from the run's artifacts).
- Before building: `tools/fixture.sh` (the test's pinned data) and, optionally, `tools/seed.sh`.
- If you change the wetness or sun model in `Model.kt`, explain why and, if you can, how it compares
  with the old one on a real rain day. `ModelTest` pins the model's output to
  `app/src/test/resources/expected.json`; the maintainer regenerates it from the reference model.
- Don't change the application id, the version scheme or the signing setup in
  `app/build.gradle.kts`: the official app's updates depend on them.
- Don't add trackers, ads, analytics or new network services without discussing it first.
- Contributions are accepted under the MIT license, the project's license.
- Wall and route corrections go to [elsalto-data](https://github.com/bucho2398/elsalto-data), not here. Don't add
  scrapers or anything else that collects data from theCrag or other sites.

## After you open one

This is maintained by one person in their free time: expect a reply within about a week. Pull
requests are reviewed, then merged or closed with an explanation. Official releases are built and
signed by the maintainer only; merged changes show up in the next official build.
