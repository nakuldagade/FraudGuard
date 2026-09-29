from fastapi import FastAPI

app = FastAPI(title="Fraud ML Service (placeholder)")


@app.get("/health")
def health():
    return {"status": "ok", "model_version": None}
