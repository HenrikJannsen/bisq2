# Release build environment

## Required build JDK

CI must request one exact Azul Zulu JDK build for Java 21. The pinned build must be the same on every operating system
used by the build and compatibility workflows. The build JDK selected by CI and the JDK archives selected by the
toolchain resolver must identify the same Java update and Zulu distribution build.

The release environment check must compare the running Gradle process with the pinned Java update, Java vendor, Java
runtime build, Zulu distribution build, and Gradle version. The Java update matches when the version numbers of the
running JDK start with those of the pinned update; each other value must match exactly. The installers bundle the JDK
of the build as their runtime, so a release build needs this exact build. The GitHub Actions workflow check must
require an exact Java build in each Java setup step, must compare it with the pinned runtime build, and must require
the pinned runtime build to be a build of the pinned Java update.

The build environment check of `check` must compare the running Gradle process with the pinned Java update, Java
vendor, and Gradle version in the same way, and must accept every build of the pinned Java update. Azul builds an
update more than once, and an installer may offer only the latest build. The builds of one update compile the same
classes; they differ in their runtime, which matters for the installers only. The check must log the runtime build and
the Zulu distribution build of the running JDK.

The Java update version does not identify a unique Zulu build. A broader CI request can select another Zulu build,
which may report a different `java.version` and fail release verification. It can also change release build inputs
without a reviewed configuration change.

## Toolchain archives

Gradle downloads a JDK through the toolchain resolver when no installed JDK matches the toolchain request. The
installers bundle the toolchain JDK as their runtime, so a modified archive on the download server must not reach a
build. The resolver must pin the SHA-256 of each archive it returns and must verify the archive before Gradle unpacks
it. A mismatch must fail the build. A platform without a pinned archive must get no archive: a JDK of another
architecture does not run.

Gradle has no checksum for a toolchain download. The resolver therefore downloads and verifies the archive itself and
stores it where Gradle looks for an archive which is already there. This location depends on the naming of the Gradle
version. The resolver must accept only Gradle versions whose naming was checked. With another naming, Gradle would
download an unchecked copy without an error.

Known limitation: Gradle sends its own request to the download server to learn the file name. A server which answers
this request with another file name than the resolver's request makes Gradle download an unchecked copy. The resolver
refuses a response with a `Content-Disposition` header, so a modified file which the server sends the same way to
every client fails the build. Gradle also downloads again without the check when unpacking the stored archive fails or
the unpacked JDK does not match the toolchain request; the resolver detects the replaced archive at the next build.
Gradle 9.0.0 cannot download a toolchain from a local `file:` URI, so it cannot take the verified local file instead.
The limitation stays until Gradle can verify a toolchain download or take a local archive.

When the build JDK changes, update the CI requests, pinned environment properties, and toolchain URLs with their
SHA-256 in the same change set. See [the Gradle notes](../../../gradle/README.md) for the current values.
