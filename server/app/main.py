"""VehicleMapping server: receives photos with GPS metadata from the Android app.

Photos land in an inbox and are recorded as `pending`; a later phase analyses them,
stores the extracted vehicle data and deletes the image.
"""

import hmac
import os
import re
import sqlite3
import time
from contextlib import closing
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse

MAX_PHOTO_BYTES = 25 * 1024 * 1024
SAFE_NAME = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}$")

SCHEMA = """
CREATE TABLE IF NOT EXISTS photos (
    id            INTEGER PRIMARY KEY,
    session       TEXT NOT NULL,
    file_name     TEXT NOT NULL,
    taken_at_ms   INTEGER NOT NULL,
    lat           REAL NOT NULL,
    lon           REAL NOT NULL,
    accuracy_m    REAL,
    speed_mps     REAL,
    bearing_deg   REAL,
    altitude_m    REAL,
    status        TEXT NOT NULL DEFAULT 'pending',
    received_at_ms INTEGER NOT NULL,
    UNIQUE (session, file_name)
);
"""


def data_dir() -> Path:
    return Path(os.environ.get("VM_DATA_DIR", "data"))


def connect() -> sqlite3.Connection:
    data_dir().mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(data_dir() / "vehiclemapping.db")
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript(SCHEMA)
    return db


def require_token(authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("VM_API_TOKEN", "")
    if not expected:
        raise HTTPException(503, "Server token not configured")
    given = (authorization or "").removeprefix("Bearer ").strip()
    if not hmac.compare_digest(given.encode(), expected.encode()):
        raise HTTPException(401, "Invalid token")


app = FastAPI(title="VehicleMapping")


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/api/stats", dependencies=[Depends(require_token)])
def stats() -> dict:
    with closing(connect()) as db:
        rows = db.execute("SELECT status, COUNT(*) FROM photos GROUP BY status").fetchall()
    return {"photos": dict(rows)}


@app.post("/api/photos", dependencies=[Depends(require_token)])
def upload_photo(
    session: str = Form(),
    file_name: str = Form(),
    epoch_ms: int = Form(),
    lat: float = Form(ge=-90, le=90),
    lon: float = Form(ge=-180, le=180),
    accuracy_m: float | None = Form(default=None),
    speed_mps: float | None = Form(default=None),
    bearing_deg: float | None = Form(default=None),
    altitude_m: float | None = Form(default=None),
    file: UploadFile = File(),
):
    """Stores one photo. Uploading the same session and file name again is a no-op."""
    if not SAFE_NAME.match(session) or not SAFE_NAME.match(file_name):
        raise HTTPException(422, "Invalid session or file name")

    with closing(connect()) as db:
        existing = db.execute(
            "SELECT id FROM photos WHERE session = ? AND file_name = ?", (session, file_name)
        ).fetchone()
        if existing:
            return {"id": existing[0], "duplicate": True}

        folder = data_dir() / "inbox" / session
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / file_name
        partial = folder / (file_name + ".part")
        size = 0
        try:
            with open(partial, "wb") as out:
                while chunk := file.file.read(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_PHOTO_BYTES:
                        raise HTTPException(413, "Photo too large")
                    out.write(chunk)
            if size == 0:
                raise HTTPException(422, "Empty photo")
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)

        with db:
            cursor = db.execute(
                """INSERT INTO photos (session, file_name, taken_at_ms, lat, lon, accuracy_m,
                                       speed_mps, bearing_deg, altitude_m, received_at_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (session, file_name, epoch_ms, lat, lon, accuracy_m, speed_mps, bearing_deg,
                 altitude_m, int(time.time() * 1000)),
            )
        return JSONResponse({"id": cursor.lastrowid, "duplicate": False}, status_code=201)
