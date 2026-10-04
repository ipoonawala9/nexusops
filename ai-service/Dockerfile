FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN apt-get update && apt-get upgrade -y --no-install-recommends && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml ./
COPY app app
# Install, then drop the packaging toolchain: the runtime needs neither pip nor setuptools
# (and their vendored libraries carry their own CVEs).
RUN pip install --no-cache-dir . \
    && pip uninstall -y pip setuptools wheel \
    && useradd --system --uid 10001 --no-create-home app
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
