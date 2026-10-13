# Contributing

Thanks for helping. Issues and pull requests go to
[bucho2398/elsalto-app](https://github.com/bucho2398/elsalto-app).

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

Official releases are built and signed by the maintainer only. Merged changes show up in the next
official build.
