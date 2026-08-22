from __future__ import annotations

import re
from dataclasses import dataclass
from typing import ClassVar


@dataclass(frozen=True, slots=True)
class RedisKeyspace:
    """Validated XA Mass Redis root and runtime scope."""

    ROOT: ClassVar[str] = "xa_mass"
    _SCOPE_PATTERN: ClassVar[re.Pattern[str]] = re.compile(
        r"(?:profile|test)_[a-z0-9_]+"
    )

    scope: str

    def __post_init__(self) -> None:
        if (
            not isinstance(self.scope, str)
            or self._SCOPE_PATTERN.fullmatch(self.scope) is None
        ):
            raise ValueError(
                "Redis scope must match profile_[a-z0-9_]+ "
                "or test_[a-z0-9_]+"
            )

    @property
    def base(self) -> str:
        return f"{self.ROOT}:{self.scope}"
