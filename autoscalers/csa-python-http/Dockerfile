FROM python:3.12-slim

WORKDIR /app

ARG PROFILE=vq

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py metrics.py evaluate.py adapt_state.py adapt_replicas.py adapt_cpu.py adapt_tag.py ./
COPY profiles/${PROFILE}.yaml /config.yaml

EXPOSE 8080

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
