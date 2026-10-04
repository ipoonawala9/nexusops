"""NexusOps AI service. Phase 12 adds RAG, classification and forecasting behind the
Spring Boot AI gateway; until then it only exposes a health endpoint."""

from fastapi import FastAPI
from pydantic import BaseModel


class Health(BaseModel):
    status: str
    service: str


app = FastAPI(title="NexusOps AI Service", version="0.1.0")


@app.get("/health", response_model=Health)
def health() -> Health:
    return Health(status="ok", service="ai-service")
