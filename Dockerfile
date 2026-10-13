# One image for Auth, the orchestrator and the image server; the command picks the service.
FROM python:3.12-slim AS build
WORKDIR /src
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir --upgrade pip && pip wheel --no-cache-dir --wheel-dir /wheels ".[push]"

FROM python:3.12-slim
RUN useradd --system --uid 10001 --home /app photon \
 && mkdir -p /var/lib/photon /app \
 && chown photon /var/lib/photon
COPY --from=build /wheels /wheels
RUN pip install --no-cache-dir /wheels/*.whl && rm -rf /wheels
COPY alembic.ini /app/alembic.ini
COPY migrations /app/migrations
WORKDIR /app
USER photon
ENV PYTHONUNBUFFERED=1 PHOTON_APP_ROOT=/app PHOTON_ENV=prod PHOTON_KEYS_DIR=/run/secrets/photon
EXPOSE 8080 8081 8082
CMD ["python", "-m", "photon.orchestrator"]
