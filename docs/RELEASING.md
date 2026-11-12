# Releasing Flack

Releases are built and signed by GitHub Actions from a tag. Testers download the newest one from a single link:

https://github.com/jamditis/flack/releases/latest/download/flack.apk

Android installs an update over an existing install only when both are signed with the same key. Keep the key safe: if it is lost, every tester has to uninstall Flack and install it again.

## One-time setup: make the signing key

Do this on your own computer, not in a cloud session, so the key never passes through anyone else. You need `keytool`, which comes with any Java JDK and with Android Studio.

1. Make the key. Pick a strong password and store it in your password manager.

   On macOS or Linux:

   ```bash
   keytool -genkeypair -keystore flack-release.jks -alias flack -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Flack"
   ```

   On Windows (PowerShell, with a JDK installed):

   ```powershell
   keytool -genkeypair -keystore flack-release.jks -alias flack -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Flack"
   ```

   `keytool` asks for the password twice. Use the same password for the key when it asks.

2. Turn the key file into text for GitHub.

   On macOS or Linux:

   ```bash
   base64 < flack-release.jks > flack-release.txt
   ```

   On Windows (PowerShell):

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("flack-release.jks")) | Out-File -Encoding ascii flack-release.txt
   ```

3. In the repository, open Settings → Secrets and variables → Actions → New repository secret, and add four secrets:

   | Name | Value |
   | --- | --- |
   | `FLACK_KEYSTORE_BASE64` | the whole contents of `flack-release.txt` |
   | `FLACK_KEYSTORE_PASSWORD` | the password from step 1 |
   | `FLACK_KEY_ALIAS` | `flack` |
   | `FLACK_KEY_PASSWORD` | the password from step 1 |

4. Delete `flack-release.txt`. Keep `flack-release.jks` and its password backed up somewhere safe and private, such as your password manager. Never commit either one; CI refuses keystore files.

## Making a release

1. Merge the changes you want into `main`.
2. Create a tag on that commit and push it. The version name is the tag without the `v`.

   ```bash
   git checkout main
   git pull
   git tag v0.1.0
   git push origin v0.1.0
   ```

   Push the tag from git as shown. If you create a release on the GitHub Releases page instead, the workflow adds the APK and checksum to that release rather than making a new one.

3. The Release workflow then:
   - refuses a tag that is not on `main`
   - runs the tests and lint
   - builds and signs the APK with the key from the secrets, then deletes the key from the runner
   - checks the APK's signature and permissions
   - publishes the release with `flack.apk`, its SHA-256 checksum and install notes, and marks it as the latest release

4. Send testers the single link above. It always points at the newest release.

## The first signed release

Earlier builds were debug-signed. Testers who installed one must uninstall it once before installing the first signed release. After that, updates install over the old version.

## If something fails

- **"Missing FLACK_KEYSTORE_BASE64 secret"**: step 3 of the setup is not done.
- **"No signed APK was produced"**: a password or the alias is wrong. Check the three other secrets.
- **The tag check fails**: the tag points at a commit that is not on `main`. Delete the tag and tag a `main` commit.
