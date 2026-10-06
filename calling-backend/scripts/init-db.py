from pathlib import Path
import sqlite3


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    db_path = root / "prisma" / "dev.db"
    migration_path = root / "prisma" / "migrations" / "202605051233_init" / "migration.sql"

    db_path.parent.mkdir(parents=True, exist_ok=True)

    if db_path.exists():
        db_path.unlink()

    sql = migration_path.read_text(encoding="utf-8")

    connection = sqlite3.connect(db_path)
    try:
        connection.execute("PRAGMA foreign_keys = ON;")
        connection.executescript(sql)
        connection.commit()
    finally:
        connection.close()

    print(f"SQLite database created at {db_path}")


if __name__ == "__main__":
    main()
