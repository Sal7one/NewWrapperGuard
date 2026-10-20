"""Local report integrations for the unchanged WrapperGuard provider API 1."""

__version__ = "0.3.0"

from .models import make_finding
from .paths import normalize_location
from .profile import TargetScope

__all__ = ["__version__", "make_finding", "normalize_location", "TargetScope"]
