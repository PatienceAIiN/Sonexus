from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


class LoginIn(BaseModel):
    email: str
    password: str


class TokenOut(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"


class DeviceRegisterIn(BaseModel):
    device_name: str
    home_name: str


class DeviceRegisterOut(BaseModel):
    device_id: int
    api_key: str


class EventIn(BaseModel):
    ts: datetime
    type: Literal["STATE_CHANGE", "OVERRIDE", "CALL"]
    room_state: Literal["QUIET", "TALKING", "BOOST"] | None = None
    action: Literal["DUCK", "BOOST", "RESTORE", "MUTE", "PAUSE", "RESUME"] | None = None
    level: int | None = None
    db: float | None = None
    source: Literal["engine", "user"] | None = None
    detail: str | None = None


class EventsIn(BaseModel):
    events: list[EventIn]


class ClipMeta(BaseModel):
    ts: datetime
    duration_ms: int
    label: str | None = None
    room_state: str | None = None


class ConsentIn(BaseModel):
    purpose: Literal["upload_clips", "telemetry", "training", "wake_word", "store_on_server"]
    granted: bool


class LabelIn(BaseModel):
    clip_id: int | None = None
    event_id: int | None = None
    label: str
    correct: bool


class TrainingRunIn(BaseModel):
    home_id: int


class MetricsQuery(BaseModel):
    home_id: int
    days: int = Field(default=7, ge=1, le=365)
