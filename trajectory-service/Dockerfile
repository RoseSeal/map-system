FROM python:3.12-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY inland_traj ./inland_traj
COPY scripts/train_gru.py ./scripts/train_gru.py
COPY server ./server

EXPOSE 8200

CMD ["uvicorn", "server.trajectory_server.server:app", "--host", "0.0.0.0", "--port", "8200"]
