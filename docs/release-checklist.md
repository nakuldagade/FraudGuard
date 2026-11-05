# Publishing checklist (13.7)

The project is pushed to GitHub in one go. Work through this top to bottom.

## Before pushing
- [ ] **Secrets scan.** `docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:latest detect --source /repo --no-git` (working tree) and, once the repository exists, `gitleaks detect` on the history. The values in `.env.example` are demo values by design. `.gitleaksignore` lists three expired test tokens from commit `ace17a3` (reviewed and allowed on purpose); any other finding is real.
- [ ] `.env` is not tracked: `git ls-files | grep -E '(^|/)\.env$'` prints nothing.
- [ ] No `.venv`, `node_modules`, `target`, `dist`, `data/raw` or `*.joblib` in git: `git ls-files | grep -E '\.venv|node_modules|/target/|/dist/|\.joblib'` prints nothing.
- [ ] No large files: `git ls-files -z | xargs -0 du -k | sort -rn | head` (nothing over a few hundred KB except `package-lock.json` and screenshots).
- [ ] `backend/src/test/resources/application-test.yml` is committed (the security tests need it).
- [ ] `make test` and `make lint` pass; `docker compose --profile app up --build` starts cleanly from a fresh clone with only `cp .env.example .env`.
- [ ] `LICENSE` present (MIT), `CHANGELOG.md` has a `## [1.0.0]` section (the release workflow takes its notes from it).

## On GitHub
- [x] README badges point at `nakuldagade/FraudGuard`.
- [ ] Repository description: *Real-time fraud detection demo: Kafka, Spring Boot, FastAPI, React. Synthetic data, not production-grade.*
- [ ] Topics: `fraud-detection`, `kafka`, `spring-boot`, `fastapi`, `react`, `prometheus`, `grafana`, `docker`, `event-driven`, `portfolio-project`.
- [ ] Settings → Actions → General: workflow permissions "Read repository contents" (the workflows request what they need).
- [ ] Turn on Dependabot alerts, secret scanning and push protection; code scanning results appear after the first Security run.
- [ ] Confirm the first CI run is green. Note: the CI and security workflows were validated with `actionlint` but **never run on GitHub**, so expect to fix small things on the first run.

## Release
- [ ] `git tag v1.0.0 && git push origin v1.0.0` triggers the Release workflow: images to `ghcr.io/<owner>/fraudguard-{backend,ml,frontend}` tagged `1.0.0` and the short commit id, and a GitHub Release with the changelog notes.
- [ ] Make the three GHCR packages public if you want them pullable without login.
- [ ] Add a 2 to 3 minute screen recording (follow `docs/demo-script.md`, sections 1, 4, 5 and 6) and link it from the README.
