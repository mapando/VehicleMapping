import sqlite3

import pytest
from fastapi.testclient import TestClient

from app.main import app

AUTH = {"Authorization": "Bearer secret"}
FIELDS = {"session": "20261122_153818", "file_name": "img_00001.jpg", "epoch_ms": "1795361898000",
          "lat": "45.4642", "lon": "9.19", "accuracy_m": "4.5", "speed_mps": "12.3"}


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("VM_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("VM_API_TOKEN", "secret")
    return TestClient(app)


def post(client, fields=FIELDS, headers=AUTH, content=b"jpegbytes"):
    return client.post("/api/photos", data=fields, headers=headers,
                       files={"file": ("x.jpg", content, "image/jpeg")})


def test_health_needs_no_token(client):
    assert client.get("/api/health").json() == {"status": "ok"}


def test_upload_stores_file_and_row(client, tmp_path):
    r = post(client)
    assert r.status_code == 201 and r.json()["duplicate"] is False
    assert (tmp_path / "inbox/20261122_153818/img_00001.jpg").read_bytes() == b"jpegbytes"
    row = sqlite3.connect(tmp_path / "vehiclemapping.db").execute(
        "SELECT lat, lon, accuracy_m, bearing_deg, status FROM photos").fetchone()
    assert row == (45.4642, 9.19, 4.5, None, "pending")


def test_second_upload_is_a_duplicate(client):
    post(client)
    r = post(client, content=b"other")
    assert r.status_code == 200 and r.json()["duplicate"] is True
    assert client.get("/api/stats", headers=AUTH).json() == {"photos": {"pending": 1}}


def test_wrong_or_missing_token_is_rejected(client):
    assert post(client, headers={"Authorization": "Bearer nope"}).status_code == 401
    assert post(client, headers={}).status_code == 401


def test_unset_server_token_rejects_everything(client, monkeypatch):
    monkeypatch.setenv("VM_API_TOKEN", "")
    assert post(client, headers={"Authorization": "Bearer "}).status_code == 503


@pytest.mark.parametrize("field,value", [("session", "../etc"), ("file_name", "a/b.jpg"),
                                         ("file_name", ".."), ("lat", "91")])
def test_bad_fields_are_rejected(client, tmp_path, field, value):
    assert post(client, fields={**FIELDS, field: value}).status_code == 422
    assert not (tmp_path / "inbox").exists()


def test_empty_photo_is_rejected(client):
    assert post(client, content=b"").status_code == 422
