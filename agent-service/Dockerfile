FROM python:3.13.5-slim-bookworm@sha256:4c2cf9917bd1cbacc5e9b07320025bdb7cdf2df7b0ceaccb55e9dd7e30987419
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY agent-service/requirements.lock /app/requirements.lock
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: -r requirements.lock \
    && groupadd --system agentops && useradd --system --gid agentops agentops
COPY --chown=agentops:agentops agent-service/agentops_agent /app/agentops_agent
COPY --chown=agentops:agentops deploy/agent-entrypoint.py /app/compose-entrypoint.py
USER agentops
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s CMD ["python", "-c", "import socket;s=socket.create_connection(('127.0.0.1',8090),2);s.close()"]
CMD ["python", "-m", "uvicorn", "agentops_agent.app:from_environment", "--factory", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
