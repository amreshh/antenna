<!--
Merging into main releases the APK this pull request builds and tests, as the
next patch version. Put #minor or #major in the title for a minor or major
release; the CI comment below shows the version.
-->

## What and why

<!-- What changes, and the problem it solves. "Fixes #123" closes an issue. -->

## Testing

<!-- What you checked beyond CI (which runs the unit tests, Android lint and the
workflow checks, builds the release APK and smoke-tests it on an emulator):
what you tried in the app, on which phone or tablet and Android version. -->

## Checklist

- [ ] README and comments match the new behaviour
- [ ] Tried the APK from the CI comment on a phone (and a tablet, for layout changes)
- [ ] New libraries that use reflection: kept in `app/proguard-rules.pro` if R8 breaks them (the smoke test shows it)
