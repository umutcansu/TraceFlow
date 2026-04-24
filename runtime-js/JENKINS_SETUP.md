# Jenkins setup for runtime-js publishing

One-time steps to wire this pipeline into the existing `umutcansu-jenkins`
Docker container.

## 1. Credential: npm token

1. Log in to npm and open https://www.npmjs.com/settings/umutcansu/tokens
2. **Generate New Token** -> **Granular Access Token**
   - Name: `jenkins-publish-traceflow`
   - Expiration: 90 days (rotate regularly)
   - Packages and scopes: "Read and write" on `@umutcansu`
3. Copy the token (starts with `npm_`). You won't see it again.
4. In Jenkins: **Manage Jenkins -> Credentials -> (global) -> Add Credentials**
   - Kind: **Secret text**
   - Secret: paste the npm token
   - ID: `npm-token-traceflow` **(must match the Jenkinsfile)**
   - Description: "npm token for @umutcansu/traceflow-runtime publish"

## 2. NodeJS tool

The pipeline expects a Node tool named `node20`:

1. **Manage Jenkins -> Tools**
2. Under "NodeJS installations", click **Add NodeJS**
   - Name: `node20`
   - Version: pick any 20.x LTS
   - Global npm packages to install: _(leave empty; we use `npm ci`)_
3. Save.

_Alternative:_ if the Jenkins container has Docker-in-Docker, you can
instead delete the `tools { nodejs ... }` block in the Jenkinsfile and
uncomment the `agent { docker { image 'node:20-alpine' } }` line to run
inside a throwaway container.

## 3. Create the job

**New Item -> Pipeline**, name it e.g. `traceflow-runtime-publish`.

- **Pipeline definition**: "Pipeline script from SCM"
- **SCM**: Git
- **Repository URL**: your clone URL (ssh or https with credentials)
- **Branch specifier**: `*/feat/multi-platform-v2` for now; switch to
  `*/main` once the branch merges.
- **Script Path**: `runtime-js/Jenkinsfile`
- **Additional Behaviours** -> **Clean before checkout** (recommended)

If you want tag-based triggering later, add:
- **Build Triggers -> Poll SCM**: `H/5 * * * *`
  and configure the job to only build on tags matching `runtime-js-v*`.

## 4. First run

1. Click **Build with Parameters**
2. `BUMP`: pick `none` for the first run (0.1.0 is already committed)
3. `DRY_RUN`: tick it for the very first run as a smoke check
4. Watch the pipeline; the "Publish (dry-run)" stage must succeed.
5. Re-run with `DRY_RUN` unticked to actually publish.

## 5. Routine releases

- Make your changes on the branch, land them.
- Run the Jenkins job with `BUMP=patch` (or `minor`/`major`).
- The pipeline:
  1. Bumps `runtime-js/package.json`
  2. Verifies the new version isn't already on npm
  3. Dry-run publishes (safety net)
  4. Real publishes
  5. Pushes the tag `runtime-js-v0.1.1` (or whatever) to origin

## Troubleshooting

| Symptom | Fix |
|---|---|
| `404 You do not have permission to publish "@umutcansu/traceflow-runtime"` | Token scope doesn't cover the package. Regenerate with `@umutcansu` scope. |
| `402 Payment Required` | Missing `--access public`. The Jenkinsfile passes it already; check your npm CLI version isn't stripping the flag. |
| `EPERM` on `rm -f .npmrc` | Docker agent running as a different UID. Pass `-u root:root` in the agent args. |
| Pipeline can't push tag | The SCM credential is read-only. Add write access or swap in an `sshagent { ... }` block around the push. |
| `ERR! 400 Bad Request - PUT ... Package requires provenance` | You don't need provenance; make sure the npm token is a classic or granular token, not an OIDC-only token. |
