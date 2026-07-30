"""Israeli-legal domain services: templates, drafting and analysis."""

from app.services.legal.analysis import AnalysisOutcome, AnalysisService
from app.services.legal.drafting import DraftingService, DraftResult
from app.services.legal.templates import (
    CONTRACT_TEMPLATES,
    LETTER_TEMPLATES,
    LegalTemplate,
    TemplateField,
    get_contract_template,
    get_letter_template,
    list_templates,
)

__all__ = [
    "CONTRACT_TEMPLATES",
    "LETTER_TEMPLATES",
    "AnalysisOutcome",
    "AnalysisService",
    "DraftResult",
    "DraftingService",
    "LegalTemplate",
    "TemplateField",
    "get_contract_template",
    "get_letter_template",
    "list_templates",
]
