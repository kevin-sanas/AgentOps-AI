"""Compose-only DSN assembly: passwords need not be URL-encoded by the operator."""
import os
from urllib.parse import quote

password = os.environ.pop("CHECKPOINT_PASSWORD")
os.environ["AGENT_CHECKPOINT_DSN"] = (
    "postgresql://agentops_checkpoint:" + quote(password, safe="")
    + "@checkpoint-db:5432/agentops_checkpoint"
)
os.execvp("python", ["python", "-m", "uvicorn", "agentops_agent.app:from_environment",
                     "--factory", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"])
