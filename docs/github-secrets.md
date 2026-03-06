# GitHub Secrets Setup

Add the following repository secrets in GitHub:

- `ODSAY_API_KEY`
- `DDAREUNGI_API_KEY`
- `TAGO_API_KEY`
- `NAVER_CLIENT_ID`
- `NAVER_CLIENT_SECRET`

Path:
- GitHub repository -> `Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret`

Notes:
- Application config now reads only environment variables (no hardcoded fallback keys).
- CI workflow (`.github/workflows/ci.yml`) injects these secrets into job environment variables.
- For local development, export the same environment variables before running the API.
