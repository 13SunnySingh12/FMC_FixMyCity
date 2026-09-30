from psycopg_pool import ConnectionPool


def open_pool(database_url: str) -> ConnectionPool:
    # min_size=0 keeps no idle connections, so Neon can scale to zero between requests.
    return ConnectionPool(database_url, min_size=0, max_size=5, max_idle=120, kwargs={"autocommit": True}, open=True)


def vector_literal(vector: list[float]) -> str:
    """pgvector text form, cast with ::vector in SQL."""
    return "[" + ",".join(f"{value:.7g}" for value in vector) + "]"
