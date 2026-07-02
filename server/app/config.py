from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str = "postgresql+asyncpg://sonex:sonex@localhost:5432/sonex"

    @field_validator("database_url")
    @classmethod
    def _force_asyncpg(cls, v: str) -> str:
        # Render/Heroku hand out postgres:// URLs; SQLAlchemy async needs the
        # asyncpg driver scheme. Accept either and normalise.
        for prefix in ("postgres://", "postgresql://"):
            if v.startswith(prefix):
                return "postgresql+asyncpg://" + v[len(prefix):]
        return v
    redis_url: str = "redis://localhost:6379/0"

    jwt_secret: str = "change-me"
    jwt_algorithm: str = "HS256"
    jwt_expires_minutes: int = 60 * 24

    # Storage
    storage_backend: str = "failover"  # failover | local
    local_storage_dir: str = "./data/objects"
    cloudinary_cloud_name: str = ""
    cloudinary_api_key: str = ""
    cloudinary_api_secret: str = ""
    r2_account_id: str = ""
    r2_access_key_id: str = ""
    r2_secret_access_key: str = ""
    r2_bucket: str = "sonex"

    # Rate limiting (requests per window seconds, per device/user)
    rate_limit_requests: int = 120
    rate_limit_window: int = 60

    min_app_version: int = 1


settings = Settings()
