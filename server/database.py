import json
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional


DEFAULT_DATABASE_PATH = Path(__file__).resolve().parent.parent / "data" / "dorolog.db"
DATABASE_PATH = Path(os.getenv("DOROLOG_DATABASE_PATH", DEFAULT_DATABASE_PATH))


def connect_database(database_path: Path = DATABASE_PATH) -> sqlite3.Connection:
    """SQLite 연결을 열고 외래 키 검사를 활성화한다."""
    database_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(database_path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def initialize_database(database_path: Path = DATABASE_PATH) -> None:
    """이야기와 저장 선택을 보관할 테이블을 만든다."""
    with connect_database(database_path) as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS stories (
                trip_id TEXT NOT NULL CHECK (length(trim(trip_id)) > 0),
                story_id TEXT NOT NULL CHECK (length(trim(story_id)) > 0),
                title TEXT NOT NULL CHECK (length(trim(title)) > 0),
                region TEXT NOT NULL CHECK (length(trim(region)) > 0),
                latitude REAL,
                longitude REAL,
                summary TEXT NOT NULL DEFAULT '',
                highlights_json TEXT NOT NULL DEFAULT '[]',
                recorded_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (trip_id, story_id),
                CHECK (
                    (latitude IS NULL AND longitude IS NULL)
                    OR
                    (latitude IS NOT NULL AND longitude IS NOT NULL
                        AND latitude BETWEEN -90.0 AND 90.0
                        AND longitude BETWEEN -180.0 AND 180.0)
                )
            );

            CREATE TABLE IF NOT EXISTS saved_stories (
                trip_id TEXT NOT NULL,
                story_id TEXT NOT NULL,
                PRIMARY KEY (trip_id, story_id),
                FOREIGN KEY (trip_id, story_id)
                    REFERENCES stories (trip_id, story_id)
                    ON DELETE CASCADE
            );
            """
        )


def upsert_story(
    trip_id: str,
    story_id: str,
    title: str,
    region: str,
    latitude: Optional[float] = None,
    longitude: Optional[float] = None,
    summary: str = "",
    highlights: Optional[list[str]] = None,
    recorded_at: Optional[str] = None,
    database_path: Path = DATABASE_PATH,
) -> None:
    """이야기를 새로 저장하거나 기존 내용을 수정한다."""
    now = datetime.now(timezone.utc).isoformat(timespec="microseconds")
    highlights_json = json.dumps(highlights or [], ensure_ascii=False)

    with connect_database(database_path) as connection:
        connection.execute(
            """
            INSERT INTO stories (
                trip_id, story_id, title, region, latitude, longitude,
                summary, highlights_json, recorded_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_id, story_id) DO UPDATE SET
                title = excluded.title,
                region = excluded.region,
                latitude = excluded.latitude,
                longitude = excluded.longitude,
                summary = excluded.summary,
                highlights_json = excluded.highlights_json,
                recorded_at = excluded.recorded_at,
                updated_at = excluded.updated_at
            """,
            (
                trip_id,
                story_id,
                title,
                region,
                latitude,
                longitude,
                summary,
                highlights_json,
                recorded_at,
                now,
                now,
            ),
        )


def list_stories(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> list[dict]:
    """여행의 이야기를 최초 등록 시각 기준 최신순으로 반환한다."""
    with connect_database(database_path) as connection:
        rows = connection.execute(
            """
            SELECT
                story_id, title, region, latitude, longitude,
                summary, highlights_json, recorded_at, created_at
            FROM stories
            WHERE trip_id = ?
            ORDER BY created_at DESC
            """,
            (trip_id,),
        ).fetchall()

    return [
        {
            "id": row["story_id"],
            "title": row["title"],
            "region": row["region"],
            "latitude": row["latitude"],
            "longitude": row["longitude"],
            "summary": row["summary"],
            "highlights": json.loads(row["highlights_json"]),
            "recordedAt": row["recorded_at"],
            "createdAt": row["created_at"],
        }
        for row in rows
    ]


def list_saved_story_ids(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> list[str]:
    """여행에서 사용자가 남기기로 선택한 이야기 ID를 반환한다."""
    with connect_database(database_path) as connection:
        rows = connection.execute(
            """
            SELECT story_id
            FROM saved_stories
            WHERE trip_id = ?
            ORDER BY story_id
            """,
            (trip_id,),
        ).fetchall()

    return [row["story_id"] for row in rows]


def replace_saved_story_ids(
    trip_id: str,
    story_ids: list[str],
    database_path: Path = DATABASE_PATH,
) -> None:
    """여행에서 남길 이야기 전체 목록을 한 번에 교체한다."""
    if len(story_ids) != len(set(story_ids)):
        raise ValueError("이야기 ID가 중복되었습니다.")

    with connect_database(database_path) as connection:
        existing_ids = {
            row["story_id"]
            for row in connection.execute(
                "SELECT story_id FROM stories WHERE trip_id = ?",
                (trip_id,),
            ).fetchall()
        }
        if not set(story_ids).issubset(existing_ids):
            raise ValueError("해당 여행에 없는 이야기는 저장할 수 없습니다.")

        connection.execute(
            "DELETE FROM saved_stories WHERE trip_id = ?",
            (trip_id,),
        )
        connection.executemany(
            "INSERT INTO saved_stories (trip_id, story_id) VALUES (?, ?)",
            [(trip_id, story_id) for story_id in story_ids],
        )
