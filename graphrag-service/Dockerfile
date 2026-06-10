FROM python:3.12-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY graphrag_service ./graphrag_service
COPY data ./data

EXPOSE 8100

CMD ["uvicorn", "graphrag_service.server:app", "--host", "0.0.0.0", "--port", "8100"]
