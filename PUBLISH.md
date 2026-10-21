# Publishing to the Gradle Plugin Portal and Maven Central

Each release tag publishes the same artifacts to both destinations: the Gradle Plugin Portal receives them through `publishPlugins`, and Maven Central receives them through `publishAndReleaseToMavenCentral`.

## One-time setup

### Gradle Plugin Portal

1. Sign in at [plugins.gradle.org](https://plugins.gradle.org/). There is no namespace to register and no GPG key to create: the Portal hosts the artifacts and handles discovery.
2. Open the **API Keys** tab in your profile and copy the key and secret. Regenerate them if they are not shown.
3. In this GitHub repository, open **Settings → Secrets and variables → Actions** and add these repository secrets:

    | Secret                  | Value                                |
    | ----------------------- | ------------------------------------ |
    | `GRADLE_PUBLISH_KEY`    | Portal API key.                      |
    | `GRADLE_PUBLISH_SECRET` | Portal API secret matching that key. |

### Maven Central

1. Sign in to the [Sonatype Central Portal](https://central.sonatype.com/). The `org.teamvan` namespace is already registered; ensure the publishing account has access to it. Both the implementation and plugin marker use its `org.teamvan.wasmbot` subnamespace.
2. Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/). Use its generated username and password, **not** the account's login credentials.
3. Create a passphrase-protected signing key using `gpg --full-generate-key`. Find its fingerprint with `gpg --list-secret-keys --keyid-format LONG`, then publish the public key and export the private key using that fingerprint:

    ```sh
    gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_FINGERPRINT
    gpg --armor --export-secret-keys YOUR_KEY_FINGERPRINT
    ```

    The export prints the private key. Copy the entire armored block into the secret below; never commit it or put it in build logs. See Sonatype's [signing requirements](https://central.sonatype.org/publish/requirements/gpg/).

4. Add these repository secrets alongside the Portal secrets:

    | Secret                   | Value                                                                          |
    | ------------------------ | ------------------------------------------------------------------------------ |
    | `MAVEN_CENTRAL_USERNAME` | Generated Central Portal token username.                                       |
    | `MAVEN_CENTRAL_PASSWORD` | Generated Central Portal token password.                                       |
    | `MAVEN_SIGNING_KEY`      | Complete ASCII-armored private key, including BEGIN/END lines and line breaks. |
    | `MAVEN_SIGNING_PASSWORD` | Signing key passphrase.                                                        |

Enable GitHub Actions and commit the publishing configuration before creating the first release tag. Every tag creation can publish, regardless of branch; restrict tag creation, updates, and deletion to release maintainers with a GitHub tag ruleset.

## Release

From this repository's root, at the intended release commit on any branch:

```sh
gradle check assemble
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin refs/tags/v0.1.0
```

Each new tag triggers a publication attempt. Its name becomes the version, with a leading `v` removed: `v0.1.0` and `0.1.0` both select version `0.1.0`. There is no tag-name or branch filter; choose a new, compatible version. Ordinary local builds default to `0.1.0` and remain unsigned.

[The publishing workflow](.github/workflows/publish.yml) listens **only to GitHub's `create` event**, with the publishing job restricted to tag creation. Ordinary pushes, tag updates/deletions, and branch creation do not publish; there is no manual trigger or release-tag validation script.

The publishing job checks out the tagged commit, runs `check`, builds the sources and Groovydoc artifacts, then publishes twice using Java 25 and Gradle 9.4.1:

- `publishPlugins` uploads the implementation JAR, the `org.teamvan.wasmbot` plugin marker, and their POM metadata to `https://plugins.gradle.org/m2`.
- `publishAndReleaseToMavenCentral` signs every publication and releases the implementation JAR, plugin marker, sources, and Javadoc artifacts to Maven Central.

Both destinations use the `org.teamvan.wasmbot` group and the `wasmbot` implementation artifact. Plugin classes and the bundled library resource use `org.teamvan.wasmbot.plugin`. If Maven Central publishing fails, the Portal publication from the earlier step has already been submitted; fix the failure and create a new version tag rather than reusing the published version. The job does not build the consumer's WASM image; run the native/browser smokes separately when runtime behavior changes.

Check the run under **Actions → Publish to Gradle Plugin Portal and Maven Central** and the [Central Portal deployments](https://central.sonatype.com/publishing/deployments). Successful Central releases are automatically submitted for publication; repository availability can take another 10–30 minutes. New plugins, and the first version of each plugin, enter the Portal's moderation queue, which can take a few days; later versions of an approved plugin usually appear within minutes. Consumers need only the `gradlePluginPortal()` repository that WPILib's generated `pluginManagement` block already declares, or `mavenCentral()` for the Central artifacts. Versions on both destinations are immutable: never move/recreate a released tag or reuse its version. See Gradle's [Plugin Publish Plugin guide](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html) and the [publishing plugin's Central guide](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) for details.

## Release size

Maven Central limits publishing per organization and calendar month: file count, release size, and release count. Only Central uploads count here; the identical Gradle Plugin Portal publication does not. The plugin JAR is by far the largest artifact this repository publishes, and the first release consumed 68 MB of an 80 MB monthly release-size budget.

[`ascope/buildLite.mjs`](ascope/buildLite.mjs) therefore trims the bundled AdvantageScope Lite frontend to the assets the viewer displays for FRC: the current FRC 3D field and the 2026 KitBot, the FRC 2D fields, and the standard joystick models. FTC fields and robots, older season models, and AdvantageScope's own documentation site are dropped, and the **Documentation** tabs load the hosted manual at <https://docs.advantagescope.org> instead. A release is now roughly 22 MB, so several releases fit within one month.

The `jar` task in [build.gradle](build.gradle) prints the plugin JAR size and fails any build run with `-PreleaseVersion=...` above 30 MB, so a frontend change cannot silently consume the quota. When `ASCOPE_COMMIT` in `ascope/buildLite.mjs` moves, revisit its `retainedBundledAssets` list. The Central Portal's **Publishing → Usage Center** reports the current thresholds and this organization's usage.
