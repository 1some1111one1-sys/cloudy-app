# Virtualization engine (vendored)

Source: https://github.com/ALEX5402/NewBlackbox at commit 89b59836c66f173756a4ae258cf379a957649820 (Apache License 2.0, see LICENSE).
Only the library modules are copied (Bcore, black-reflection, compiler). Local changes:
- Bcore/build.gradle: Java source/target level 21 -> 17 (so it builds with this project's JDK 17 and links with the app).

This engine lets an app run INSIDE CloudDroid without being installed in Android: it loads the guest app's code in
CloudDroid's own processes and redirects its system calls to a private copy of its files. It relies on hidden Android
APIs and native hooks, so it can break on some Android versions / vendors, and some apps (Google Play Games, banking,
many online games) refuse to run in it.
