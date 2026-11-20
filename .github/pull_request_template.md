## What and why

<!-- What the change does, and the issue or the plan in docs/plans/ it comes from. -->

## Checks

- [ ] `./gradlew jvmTest checkKotlinAbi`
- [ ] The public API changed on purpose: `./gradlew updateKotlinAbi`, said in the commit
- [ ] The interface changed: the screenshots verified or recorded (see CONTRIBUTING.md)
- [ ] A stored format or the Room schema changed: a migration, with its test
- [ ] What travels between devices changed: said here, `ProtocolFormatTest` recorded again
