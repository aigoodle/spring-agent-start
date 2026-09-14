FROM python:3.12-slim
WORKDIR /app
COPY main.py host_client.py manifest.json ./
USER 65532:65532
EXPOSE 8091
CMD ["python", "main.py"]
