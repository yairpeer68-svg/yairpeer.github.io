import uuid
from datetime import datetime
from typing import Literal
from pydantic import BaseModel, Field

class ProjectCreate(BaseModel):
    name: str = Field(min_length=1,max_length=160)
    description: str | None = Field(default=None,max_length=10000)
    project_type: Literal["auto","python","node","flutter","android","mixed"] = "auto"
    settings: dict = Field(default_factory=dict)

class ProjectOut(BaseModel):
    id: uuid.UUID; name: str; description: str | None; project_type: str; status: str; created_at: datetime; updated_at: datetime
    model_config={"from_attributes":True}

class RunCreate(BaseModel):
    goal: str = Field(min_length=3,max_length=20000)

class RunOut(BaseModel):
    id: uuid.UUID; project_id: uuid.UUID; goal: str; status: str; stage: str; progress: int; plan_json: dict; quality_json: dict; repair_attempts: int; error: str | None; created_at: datetime; started_at: datetime | None; finished_at: datetime | None
    model_config={"from_attributes":True}

class TaskOut(BaseModel):
    id: uuid.UUID; role: str; title: str; description: str; status: str; sequence: int; attempts: int; output_json: dict
    model_config={"from_attributes":True}

class EventOut(BaseModel):
    id: uuid.UUID; task_id: uuid.UUID | None; level: str; event_type: str; message: str; data_json: dict; created_at: datetime
    model_config={"from_attributes":True}

class ApprovalDecision(BaseModel):
    decision: Literal["approved","rejected"]
    note: str | None = Field(default=None,max_length=2000)

class ApprovalOut(BaseModel):
    id: uuid.UUID; run_id: uuid.UUID; task_id: uuid.UUID | None; kind: str; reason: str; status: str; requested_by_agent: str | None; created_at: datetime; decided_at: datetime | None
    model_config={"from_attributes":True}
