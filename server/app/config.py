from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str = "postgresql+asyncpg://sonex:sonex@localhost:5432/sonex"

    @field_validator("database_url")
    @classmethod
    def _force_asyncpg(cls, v: str) -> str:
        # Render/Heroku/Neon hand out postgres:// URLs with libpq-style query
        # params; the asyncpg driver needs its own scheme and param names.
        for prefix in ("postgres://", "postgresql://"):
            if v.startswith(prefix):
                v = "postgresql+asyncpg://" + v[len(prefix):]
        if "?" in v:
            base, query = v.split("?", 1)
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            params.pop("channel_binding", None)  # asyncpg handles SCRAM itself
            if "sslmode" in params:  # asyncpg calls it `ssl`
                params["ssl"] = params.pop("sslmode")
            v = base + ("?" + "&".join(f"{k}={val}" for k, val in params.items()) if params else "")
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

    # Email (Brevo) — OTP verification, password reset, contact form
    brevo_api_key: str = ""
    brevo_sender_email: str = "info@patienceai.in"
    brevo_sender_name: str = "SoNex"
    contact_to_email: str = "info@patienceai.in"
    feedback_to_email: str = "growth@patienceai.in"
    google_site_verification: str = ""  # GOOGLE_SITE_VERIFICATION env var
    site_url: str = ""  # e.g. https://sonex.patienceai.in — overrides host detection

    # Rate limiting (requests per window seconds, per device/user)
    rate_limit_requests: int = 120
    rate_limit_window: int = 60

    min_app_version: int = 1

    # Admin dashboard (login-gated system monitor)
    admin_username: str = "admin"
    admin_password: str = ""  # ADMIN_PASSWORD env var — dashboard disabled if empty

    # Periodic per-home retraining (hours); 0 disables
    retrain_interval_hours: int = 6


settings = Settings()
