# syntax=docker/dockerfile:1
# ---- build stage: install dependencies and TRAIN the baseline model (deterministic, seed 42) ----
FROM python:3.15-slim AS build
WORKDIR /srv
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY training ./training
# Model artifacts are not stored in git (see "ML model artifacts" in docs/architecture.md): the image makes its own
# isoforest-v1 from synthetic data. With no registry.json the service serves isoforest-v1.
RUN python training/train.py

# ---- run stage: slim, non-root ----
FROM python:3.15-slim
RUN useradd --system --uid 10001 --no-create-home fraudguard
WORKDIR /srv
COPY --from=build /opt/venv /opt/venv
COPY --from=build /srv/app ./app
COPY --from=build /srv/training ./training
COPY --from=build /srv/models ./models
# The service writes the registry on a promotion and reads it on reload, so models/ must be writable
RUN mkdir -p /srv/data && chown -R fraudguard /srv/models /srv/data
ENV PATH="/opt/venv/bin:$PATH" PYTHONUNBUFFERED=1
USER fraudguard
EXPOSE 8000
# ENV, ADMIN_TOKEN and SERVICE_TOKEN come from the environment; with ENV != local the service refuses to start without tokens
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
