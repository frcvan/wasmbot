# Publishing to the Gradle Plugin Portal

## One-time setup

1. Sign in at [plugins.gradle.org](https://plugins.gradle.org/). There is no namespace to register and no GPG key to create: the Portal hosts the artifacts and handles discovery.
2. Open the **API Keys** tab in your profile and copy the key and secret. Regenerate them if they are not shown.
3. In this GitHub repository, open **Settings → Secrets and variables → Actions** and add these repository secrets:

    | Secret                 | Value                                  |
    | ---------------------- | -------------------------------------- |
    | `GRADLE_PUBLISH_KEY`   | Portal API key.                        |
    | `GRADLE_PUBLISH_SECRET`| Portal API secret matching that key.   |

4. Enable GitHub Actions and commit the publishing configuration before creating the first release tag. Every tag creation can publish, regardless of branch; restrict tag creation, updates, and deletion to release maintainers with a GitHub tag ruleset.

## Release

From this repository's root, at the intended release commit on any branch:

```sh
gradle check assemble
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin refs/tags/v0.1.0
```

Each new tag triggers a publication attempt. Its name becomes the version, with a leading `v` removed: `v0.1.0` and `0.1.0` both select version `0.1.0`. There is no tag-name or branch filter; choose a new, Portal-compatible version. Ordinary local builds default to `0.1.0` and never publish.

[The publishing workflow](.github/workflows/publish.yml) listens **only to GitHub's `create` event**, with the publishing job restricted to tag creation. Ordinary pushes, tag updates/deletions, and branch creation do not publish; there is no manual trigger or release-tag validation script.

The publishing job checks out the tagged commit, runs `check`, builds the sources and Javadoc artifacts, and calls `publishPlugins` using Java 25 and Gradle 9.4.1. The `com.gradle.plugin-publish` plugin uploads both the implementation JAR and the `org.teamvan.wasmbot` plugin marker to `https://plugins.gradle.org/m2`, along with their POM metadata. Consumers need only the `gradlePluginPortal()` repository that WPILib's generated `pluginManagement` block already declares. Plugin classes and the bundled library resource use `org.teamvan.wasmbot.plugin`. The job does not build the consumer's WASM image; run the native/browser smokes separately when runtime behavior changes.

Check the run under **Actions → Publish to Gradle Plugin Portal**. New plugins, and the first version of each plugin, enter the Portal's moderation queue, which can take a few days; later versions of an approved plugin usually appear within minutes. Portal versions are immutable: never move/recreate a released tag or reuse its version. If an upload fails, fix the issue and create a new version tag rather than triggering a tag update. See Gradle's [Plugin Publish Plugin guide](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html) for details.
