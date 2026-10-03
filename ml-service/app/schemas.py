"""Request/response shapes of the scoring API (validated automatically by FastAPI/Pydantic)."""
from typing import Optional

from pydantic import BaseModel, Field


class ScoreRequest(BaseModel):
    """One transaction plus its recent-behaviour features (computed by the backend from Redis)."""

    transaction_id: str = Field(min_length=1, max_length=64)
    amount: float = Field(gt=0)
    hour_of_day: int = Field(ge=0, le=23)             # UTC hour of the transaction
    transaction_count_5m: int = Field(ge=0)           # transactions in the last 5 minutes (incl. this one)
    transaction_count_1h: int = Field(ge=0)           # ...in the last hour (incl. this one)
    history_count: int = Field(ge=0)                  # earlier transactions behind the baseline below
    average_amount: float = Field(ge=0)               # account's usual amount BEFORE this transaction
    amount_z_score: Optional[float] = None            # None = not enough history
    seconds_since_previous: Optional[int] = Field(default=None, ge=0)   # None = first transaction
    new_device: bool
    new_merchant: bool
    country_changed: bool


class ScoreResponse(BaseModel):
    transaction_id: str
    anomaly_score: float          # higher = more unusual. NOT a probability of fraud.
    model_version: str            # which trained model produced the score
    feature_schema_version: str   # which feature layout the model expects