# Explicit acceptance/setup build only; the provider never runs Docker builds/pulls.
FROM python@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258
RUN python -m pip install --no-cache-dir bandit==1.9.4 semgrep==1.181.0
# Runtime always overrides entrypoint, user, workdir, networking and resource limits.
