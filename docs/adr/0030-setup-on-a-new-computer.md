# ADR-0030: Setup on a new computer

- Status: Accepted (owner request; Q54 answered by the lead architect). Extends ADR-0021 (launcher) and ADR-0028
  (folder shortcut).

## Request
"I tried downloading it on another computer: `mvn is not recognized`. Make the setup for a person who wants to use the
application" — double-click, nothing typed in a terminal.

## Decision (Q54)
1. **Maven Wrapper.** `mvnw.cmd` / `mvnw` and `.mvn/wrapper/maven-wrapper.properties` are committed. Maven 3.9.11 is
   pinned and its download is verified with `distributionSha256Sum`; that hash was checked against the official
   `.sha512`. We use the `only-script` wrapper type: the script downloads Maven itself, so there is no wrapper jar
   (no binary in git), which is a small deviation from the architect's list. `create-shortcut.ps1` and `start.bat`
   use `mvnw.cmd`, and fall back to `mvn` only if the wrapper is missing. The build already downloads Node
   (frontend-maven-plugin, ADR-0022), so a JDK is the only prerequisite.
2. **Finding Java** (`installer/JavaSetup.ps1`). The script checks, in order: `JAVA_HOME`, then `java` on PATH, then the
   standard install folders (Microsoft, Eclipse Adoptium, Java, Zulu, Corretto, BellSoft… under Program Files and
   `%LOCALAPPDATA%\Programs`). It accepts only a **JDK** (`java`, `javaw` and `javac` present) whose `java -version`
   is 21 or newer; with several, the newest wins, and on a tie the first candidate. If none is found, it asks y/N and
   runs `winget install --id Microsoft.OpenJDK.21 -e --accept-package-agreements --accept-source-agreements`, then
   searches again in the standard folders, because the window's PATH is stale. `JAVA_HOME` is set for this process
   only. Without winget, or if the install fails, it prints the Temurin 21 download link and stops.
3. **Errors.** The full build output goes to `logs\install-<time>.log`. On failure, one short message plus the log
   path; download problems (no internet, proxy, antivirus) are recognised and named as such.
4. **`Install PDF Generator.bat`** runs the script with `-ExecutionPolicy Bypass`, quotes `%~dp0` (paths with
   spaces), and always ends with `pause`, so the message can be read.
5. **No Git needed.** The README tells users to use GitHub's *Code → Download ZIP*, unzip the project and
   double-click the installer. `.gitattributes` keeps CRLF for `.bat`, `.cmd` and `.ps1`, and LF for `mvnw`.
6. **README** has *Install on a new computer*, *Update* and *Uninstall* sections.

## Alternatives rejected
- **A GitHub Release with a prebuilt jar** (best for end users: no build), deferred. It needs a release process,
  versions and published hashes. Recorded in `docs/PLAN.md`.
- Bundling a JDK or a jlink runtime in the repository: large OS-specific binaries in git.
- Installing Maven via winget: another tool, and not pinned.
- Requiring Git: not needed by users.

## Consequences
- The first install needs internet and a few minutes (Maven, Node, npm packages, libraries).
- `installer\JavaSetup.Tests.ps1` checks the version parsing (`"21.0.2"`, `"17.0.9"`, `"1.8.0_391"`, `"25"`,
  `"22-ea"`, no version).
- `create-shortcut.ps1 -BuildOnly` builds and checks the jar without installing anything (used to test the setup).
- Verified on a fresh clone with neither Maven nor Java on PATH and no `JAVA_HOME`: the JDK was found in Program
  Files, the wrapper downloaded and verified Maven, Node was installed and the jar built. The winget branch was not
  run here, because this machine already has a JDK. Check it once in Windows Sandbox.
