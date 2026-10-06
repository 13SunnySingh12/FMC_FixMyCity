from psycopg_pool import ConnectionPool


def open_pool(database_url: str) -> ConnectionPool:
    # min_size=0 keeps no idle connections, so Neon can scale to zero between requests. A connection is checked
    # when it is handed out, so one the server has closed (restart, failover) is replaced instead of failing a request.
    return ConnectionPool(
        database_url,
        min_size=0,
        max_size=5,
        max_idle=120,
        timeout=10,
        check=ConnectionPool.check_connection,
        kwargs={"autocommit": True},
        open=True,
    )


def vector_literal(vector: list[float]) -> str:
    """pgvector text form, cast with ::vector in SQL."""
    return "[" + ",".join(f"{value:.7g}" for value in vector) + "]"
