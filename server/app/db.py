from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from .config import settings


class Base(DeclarativeBase):
    pass


_pool_kwargs = (
    # Warm pooled connections: no TCP+TLS+auth handshake on the request path.
    dict(pool_size=10, max_overflow=20, pool_pre_ping=True, pool_recycle=300)
    if settings.database_url.startswith("postgresql") else {}
)
engine = create_async_engine(settings.database_url, echo=False, **_pool_kwargs)
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_db() -> AsyncIterator[AsyncSession]:
    async with SessionLocal() as session:
        yield session
