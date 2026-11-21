# GitHub Actions

Integration owner D adds `ci.yml` once an application module has a reproducible build command. The documentation baseline has no executable workflow.

1. Run CI for pull requests targeting `main` and pushes to `main`. Start with a backend job that installs the JDK documented by Backend and runs its checked-in Gradle wrapper build and tests from the verified working directory. Use synthetic or approved sanitized fixtures to keep provider access and credentials out of PR checks.
2. Add a frontend production build and relevant browser tests once A validates the frontend toolchain. Install the toolchain and run the exact command established in the [frontend README](../../frontend/README.md). Verify the static entry point and its built assets exist.
3. Add an analytics test job when C publishes a repeatable test command. Use redistributable fixtures so the restricted incident CSV stays off CI.
4. Use read-only repository access for build and test jobs. Have D verify that checks run on a PR and a merge to `main` before asking the repository owner to require them.

Follow the [test plan](../../docs/TESTING.md) for what each job should verify and the [deployment runbook](../../docs/DEPLOYMENT.md) for public smoke tests. [GitHub Actions workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
