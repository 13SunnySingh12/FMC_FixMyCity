from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """From environment variables, or the repository-root .env when run locally (environment wins)."""

    model_config = SettingsConfigDict(env_file="../.env", extra="ignore")

    database_url: str
    ai_service_api_key: str
    gemini_api_key: str
    groq_api_key: str

    groq_model: str = "openai/gpt-oss-120b"
    # Tried in order. Gemini models see demand spikes (HTTP 503), so a second model keeps vision available.
    gemini_models: str = "gemini-3.5-flash-lite,gemini-3.5-flash"
    gemini_embedding_model: str = "gemini-embedding-2"
    knowledge_dir: str = "knowledge"

    @property
    def gemini_model_list(self) -> list[str]:
        return [model.strip() for model in self.gemini_models.split(",") if model.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
