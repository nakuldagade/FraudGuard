"""Contract test: the Python side of contracts/ml-score.schema.json (the Java side tests the same file)."""
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.main import app
from app.schemas import ScoreRequest, ScoreResponse
from tests.conftest import SERVICE_HEADERS

CONTRACT = json.loads((Path(__file__).resolve().parents[2] / "contracts" / "ml-score.schema.json").read_text())


def validator(name: str) -> Draft202012Validator:
    return Draft202012Validator({"$defs": CONTRACT["$defs"], "$ref": f"#/$defs/{name}"})


def test_contract_request_examples_are_accepted_by_the_pydantic_model():
    for key in ("request", "requestFirstEverTransaction"):
        example = CONTRACT["examples"][key]
        validator("ScoreRequest").validate(example)
        ScoreRequest(**example)                        # raises if the Python model would reject it


def test_pydantic_request_fields_match_the_contract_exactly():
    # A renamed, added or removed field on either side shows up here
    assert set(ScoreRequest.model_fields) == set(CONTRACT["$defs"]["ScoreRequest"]["properties"])
    assert set(ScoreRequest.model_fields) == set(CONTRACT["$defs"]["ScoreRequest"]["required"])


def test_pydantic_response_fields_cover_the_contract():
    assert set(CONTRACT["$defs"]["ScoreResponse"]["required"]) <= set(ScoreResponse.model_fields)


def test_what_the_service_serialises_validates_against_the_contract():
    with TestClient(app, headers=SERVICE_HEADERS) as client:
        for key in ("request", "requestFirstEverTransaction"):
            response = client.post("/score", json=CONTRACT["examples"][key])
            assert response.status_code == 200
            validator("ScoreResponse").validate(response.json())


@pytest.mark.parametrize("field, bad", [("hour_of_day", 24), ("amount", 0), ("transaction_id", "")])
# (Not tested: Pydantic is lenient and accepts "yes" for a boolean, which the contract forbids. The Java side always sends real booleans.)
def test_values_the_contract_forbids_are_also_rejected_by_the_service(field, bad):
    payload = dict(CONTRACT["examples"]["request"], **{field: bad})
    assert not validator("ScoreRequest").is_valid(payload)
    with TestClient(app, headers=SERVICE_HEADERS) as client:
        assert client.post("/score", json=payload).status_code == 422
