# Publishing to Maven Central

## One-time setup

1. Sign in to the [Sonatype Central Portal](https://central.sonatype.com/). The `org.teamvan` namespace is already registered; ensure the publishing account has access to it. Both the implementation and plugin marker use its `org.teamvan.wasmbot` subnamespace.
2. Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/). Use its generated username and password, **not** the account's login credentials.
3. Create a passphrase-protected signing key using `gpg --full-generate-key`. Find its fingerprint with `gpg --list-secret-keys --keyid-format LONG`, then publish the public key and export the private key using that fingerprint:

    ```sh
    gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_FINGERPRINT
    gpg --armor --export-secret-keys YOUR_KEY_FINGERPRINT
    ```

    The export prints the private key. Copy the entire armored block into the secret below; never commit it or put it in build logs. See Sonatype's [signing requirements](https://central.sonatype.org/publish/requirements/gpg/).

4. In this GitHub repository, open **Settings → Secrets and variables → Actions** and add these repository secrets:

    | Secret                   | Value                                                                          |
    | ------------------------ | ------------------------------------------------------------------------------ |
    | `MAVEN_CENTRAL_USERNAME` | Generated Central Portal token username.                                       |
    | `MAVEN_CENTRAL_PASSWORD` | Generated Central Portal token password.                                       |
    | `MAVEN_SIGNING_KEY`      | Complete ASCII-armored private key, including BEGIN/END lines and line breaks. |
    | `MAVEN_SIGNING_PASSWORD` | Signing key passphrase.                                                        |

5. Enable GitHub Actions and commit the publishing configuration before creating the first release tag. Every tag creation can publish, regardless of branch; restrict tag creation, updates, and deletion to release maintainers with a GitHub tag ruleset.

## Release

From this repository's root, at the intended release commit on any branch:

```sh
gradle check assemble
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin refs/tags/v0.1.0
```

Each new tag triggers a publication attempt. Its name becomes the version, with a leading `v` removed: `v0.1.0` and `0.1.0` both select version `0.1.0`. There is no tag-name or branch filter; choose a new, Maven Central-compatible version. Ordinary local builds default to `0.1.0` and remain unsigned.

[The publishing workflow](.github/workflows/publish.yml) listens **only to GitHub's `create` event**, with the publishing job restricted to tag creation. Ordinary pushes, tag updates/deletions, and branch creation do not publish; there is no manual trigger or release-tag validation script.

The publishing job checks out the tagged commit, runs `check`, builds the sources and Groovydoc artifacts, signs all publications, and calls `publishAndReleaseToMavenCentral` using Java 25 and Gradle 9.4.1. It publishes both the implementation JAR and plugin marker with their POM metadata. Plugin classes and the bundled library resource use `org.teamvan.wasmbot.plugin`. It does not build the consumer's WASM image; run the native/browser smokes separately when runtime behavior changes.

Watch **Actions → Publish to Maven Central** and the [Central Portal deployments](https://central.sonatype.com/publishing/deployments). Successful releases are automatically submitted for publication; repository availability can take another 10–30 minutes. Maven Central versions are immutable: never move/recreate a released tag or reuse its version. If an upload fails, inspect its deployment before retrying; fix the issue and create a new version tag rather than triggering a tag update. See the [publishing plugin's Central guide](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) for deployment details.
