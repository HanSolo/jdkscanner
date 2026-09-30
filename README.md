# jdk-update-scanner

One-shot CLI that finds JDK/JRE installations on the local machine, checks each one's vendor and
version against foojay disco-api, and reports which ones have a newer build available - GA or EA,
matching whichever channel the install itself is on.

Builds with Gradle. Compilation itself was verified in the environment this was written in (a
local JDK 21 was available); dependency resolution was not - that sandbox's outbound network is
allowlisted and returns 403 for both Maven Central and the Gradle plugin portal, so
`com.google.code.gson:gson` and the `shadow` plugin couldn't actually be downloaded there. The
first real `gradle build` on a machine with normal internet access is the first time dependency
resolution gets exercised.

## Build

No `gradlew` wrapper is included - generating one needs `services.gradle.org`, which wasn't
reachable from the sandbox this was written in. Run once, from a machine with normal internet
access, to add it to the project (then commit `gradlew`, `gradlew.bat` and `gradle/wrapper/`):

```
gradle wrapper --gradle-version 8.14.3
```

After that, or if you already have Gradle installed locally:

```
./gradlew build
```

`build` produces **two** jars in `build/libs/` - the shadow plugin wires its `shadowJar` task into
`assemble` (which `build` depends on) automatically, no extra command needed:
- `jdk-update-scanner.jar` - the fat jar, Gson bundled in, runnable standalone. **This is the one to run.**
- `jdk-update-scanner-1.0.0.jar` - the plain jar from Gradle's normal `jar` task, Gson NOT included -
  running this one directly fails with `NoClassDefFoundError`.

## Run

```
java -jar build/libs/jdk-update-scanner.jar
```

Options:
- `--base-url URL` - disco-api base URL (default `http://hansolo.eu:8080`).
- `--walk DIR` - an extra directory to search recursively (max depth 4) for JDK installs the
  known-location scan won't find (a custom tools folder, etc). Repeatable.
- `--json` - print one JSON document to stdout instead of the human-readable report, for scripting.
  In this mode stdout carries **only** that JSON document, nothing else, so `| jq` etc. just works.

## JSON output (`--json`)

```json
{
  "machine": {
    "operating_system": "linux",
    "architecture": "x64",
    "libc_type": null,
    "libc_type_determined": false
  },
  "installations": [
    {
      "home": "/usr/lib/jvm/temurin-21-jdk-amd64",
      "source": "known install dir (/usr/lib/jvm)",
      "java_version": "21.0.4",
      "vendor": "Eclipse Adoptium",
      "package_type": "jdk",
      "javafx_bundled": false,
      "distribution": "temurin",
      "install_manager": "sdkman",
      "release_status": "ga",
      "request_successful": true,
      "status": "update_available",
      "candidates": [
        {
          "libc_type": "glibc",
          "latest_version": "21.0.5+11",
          "update_available": true,
          "download_uri": "https://.../OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz",
          "update_command": "sdk install java 21.0.5-tem   (best-effort candidate id - run `sdk list java` to confirm the exact identifier if this doesn't match)"
        },
        {
          "libc_type": "musl",
          "latest_version": "21.0.5+11",
          "update_available": true,
          "download_uri": "https://.../OpenJDK21U-jdk_x64_linux-musl_hotspot_21.0.5_11.tar.gz",
          "update_command": "sdk install java 21.0.5-tem   (best-effort candidate id - run `sdk list java` to confirm the exact identifier if this doesn't match)"
        }
      ]
    }
  ]
}
```

`request_successful` is `false` only when the disco-api call itself failed (network error, bad
response, etc. - `status` will be `"error"` in that case, with `message` explaining what happened).
It's `true` for every other outcome, including a skipped install where no request was even made -
it answers "did the lookup fail", not "was an update found".

`status` is one of `update_available`, `up_to_date`, `skipped_unknown_vendor`, `skipped_no_version`,
`skipped_unparseable_version`, `no_matching_build`, or `error`. Every status other than
`update_available`/`up_to_date` carries a `message` field explaining why instead of a `candidates`
array - check `status` before assuming `candidates` is present.

`candidates` normally has exactly one entry; it has more than one only when `machine.libc_type` is
`null` (glibc-vs-musl detection was inconclusive on a Linux install) and disco-api has GA builds
under more than one `libc_type` for that distribution/version/OS/architecture - one candidate per
`libc_type` found, so a script can pick the one it actually needs.

`install_manager` is `"sdkman"`, `"brew"`, or `"snap"` when the install path matches one of those
tools' layouts, otherwise absent. When present AND an update is available, each candidate also
gets an `update_command` - see "Update commands" below for how reliable each one is.

`release_status` is `"ga"` or `"ea"` - it's decided by what's actually installed (an early-access
tag, e.g. `"26-ea+15"`, in the install's own `JAVA_VERSION`), not chosen by the tool. An EA install
is checked against disco-api's EA catalog and an EA candidate's `latest_version` will be another EA
build - see "Early access installs" below for why that channel needs different comparison logic.

The tool's exit code is always 0 regardless of what was found (including `error` entries) - a
script that needs to act on "was there an update" should inspect the JSON, e.g.:

```bash
# Count installs with an available update:
java -jar jdk-update-scanner.jar --json | jq '[.installations[] | select(.status == "update_available")] | length'

# Find installs whose disco-api lookup itself failed (worth retrying):
java -jar jdk-update-scanner.jar --json | jq '[.installations[] | select(.request_successful == false)]'
```

## How detection works, and its real limits

There is no single reliable way to enumerate "every JDK on this machine" across operating systems.
This combines:
1. `JAVA_HOME` and whatever `java` is first on `PATH`.
2. Known per-OS install directories, including package/version-manager layouts:
    - Both Linux and macOS: `~/.sdkman/candidates/java/<candidate-id>` (sdkman).
    - macOS: `/Library/Java/JavaVirtualMachines`, `/opt/homebrew/Cellar` and `/usr/local/Cellar`
      (Homebrew, Apple silicon and Intel prefixes respectively).
    - Linux: `/usr/lib/jvm`, `/opt`, `/usr/java`, `/home/linuxbrew/.linuxbrew/Cellar` (Linuxbrew),
      `/snap` (snap - tries `<name>/current/jdk` then `<name>/current`, since packagers nest
      differently).
    - Windows: `%ProgramFiles%\Java`, Adoptium's and Zulu's own installer directories. (sdkman isn't
      usable from native Windows - only WSL/Cygwin/git-bash - so it's Linux/macOS only here.)
3. Optional `--walk` roots, searched shallowly for a JDK's `release` file.

A JDK dropped somewhere none of these look, on Windows without checking the registry (not
implemented - would need either a `reg query` shell-out or JNI), is invisible to this tool unless
you point `--walk` at it directly.

## Update commands

When `install_manager` is detected, the command to run comes directly from the install's own path
structure for Homebrew and snap - both bake an unambiguous, version-independent identifier into the
path (`Cellar/<formula>/<version>/...`, `/snap/<name>/...`), so `brew upgrade <formula>` and
`sudo snap refresh <name>` are exact, not guessed.

sdkman is different: its candidate directory name IS a full version+vendor identifier (e.g.
`21.0.4-tem`), but there's no such identifier for the *new* version until you construct one. This
tool takes the currently-installed identifier's vendor suffix (everything from the last `-` onward)
and pairs it with the new version number - a reasonable bet given sdkman's own naming convention,
but genuinely a guess, unlike the brew/snap commands. The output always says so and suggests
`sdk list java` to confirm.

## JavaFX detection differs before and after JDK 9

`javafx_bundled` must match disco-api's own filter of the same name, or the update offered may not
correspond to what's actually installed. Detection differs by era, because JavaFX itself was
packaged completely differently:

- **JDK 9+**: the `release` file's `MODULES` property (JEP 220) lists every module in the image -
  presence of any `javafx.*` module means it's bundled.
- **JDK 8 and earlier**: there's no module system and no `MODULES` property at all. JavaFX shipped
  as a plain `jfxrt.jar` under `lib/ext`, at `<home>/jre/lib/ext/jfxrt.jar` for a full JDK (which
  bundles its own `jre/`) or `<home>/lib/ext/jfxrt.jar` for a standalone JRE - both paths are
  checked, since which layout applies isn't known in advance.
- A JDK 9+ image missing `MODULES` for some other reason falls back to running
  `bin/java --list-modules` and checking its output - deliberately not a `jmods/` directory check,
  since `jmods/` is only present in a "full SDK with jmods" distribution and is absent from a
  jlink'd custom runtime image or a "no-jmods" download regardless of whether JavaFX is actually
  bundled.

## Vendor mapping is best-effort

Matching an installed JDK's `release` file `IMPLEMENTOR` string (e.g. `"Eclipse Adoptium"`,
`"Azul Systems, Inc."`, `"SAP SE"`) to a disco-api distribution key is a hardcoded table in
`VendorMapping.java`. Vendors are free to change this string between releases, and it isn't
exhaustive - an unrecognized vendor is reported as found but skipped for the update check, not
silently dropped, so gaps are visible in the output rather than hidden.

## Version comparison is a heuristic, not JEP 223

`VersionComparator` compares version strings by their numeric components left to right, primarily
ignoring the build number (the `+N` suffix) - two GA releases essentially always differ in
FEATURE.INTERIM.UPDATE.PATCH already, so the build number carries no extra comparison-relevant
information there, and ignoring it means an install's locally-reported version (which never has a
`+build` suffix) is still comparable against disco-api's version (which always has one). Not a full
implementation of JEP 223 ordering, but good enough to flag "there's a newer build" for a human to
read, not to wire into an unattended auto-update without review.

## Early access installs need the build number

Early-access builds break the assumption above: two EA drops of the same upcoming feature release
(e.g. `26-ea+15` and `26-ea+16`) are near-certain to share an identical FEATURE.INTERIM.UPDATE.PATCH
tuple - the build number is the ONLY thing that changes week to week. If the comparison kept
ignoring it, an EA install would show "up to date" forever, no matter how many newer EA drops
disco-api had.

So `VersionComparator.isNewer()` uses the build number as a **tie-breaker**: only consulted when the
main tuple is otherwise exactly equal. This makes EA comparison actually work while leaving GA
comparison unaffected in every real case (GA releases differ in the main tuple, so the tie-break
clause never fires for them).

Detection of which channel an install is on comes from `VersionComparator.isEarlyAccess()`, which
checks for a `-ea` tag in the raw `JAVA_VERSION` (case-insensitive, e.g. `"26-ea+15"`). That decides
`release_status` for the disco-api query (`ga` vs `ea`) - an EA install is never compared against
the GA catalog, since GA and EA are different release trains that don't converge until the feature
version actually ships.