import json
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from uuid import uuid4


DEFAULT_DATABASE_PATH = Path(__file__).resolve().parent.parent.parent / "data" / "dorolog.db"
DATABASE_PATH = Path(os.getenv("DOROLOG_DATABASE_PATH", DEFAULT_DATABASE_PATH))


def connect_database(database_path: Path = DATABASE_PATH) -> sqlite3.Connection:
    """SQLite 연결을 열고 외래 키 검사를 활성화한다."""
    database_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(database_path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def initialize_database(database_path: Path = DATABASE_PATH) -> None:
    """여행, 이야기와 저장 선택을 보관할 테이블을 만든다."""
    with connect_database(database_path) as connection:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS trips (
                trip_id TEXT PRIMARY KEY CHECK (length(trim(trip_id)) > 0),
                origin_name TEXT NOT NULL CHECK (length(trim(origin_name)) > 0),
                origin_latitude REAL,
                origin_longitude REAL,
                destination_name TEXT,
                destination_latitude REAL,
                destination_longitude REAL,
                status TEXT NOT NULL CHECK (status IN ('active', 'finished')),
                started_at TEXT NOT NULL,
                ended_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                CHECK (
                    (origin_latitude IS NULL AND origin_longitude IS NULL)
                    OR
                    (origin_latitude IS NOT NULL AND origin_longitude IS NOT NULL
                        AND origin_latitude BETWEEN -90.0 AND 90.0
                        AND origin_longitude BETWEEN -180.0 AND 180.0)
                ),
                CHECK (
                    (destination_latitude IS NULL AND destination_longitude IS NULL)
                    OR
                    (destination_latitude IS NOT NULL AND destination_longitude IS NOT NULL
                        AND destination_latitude BETWEEN -90.0 AND 90.0
                        AND destination_longitude BETWEEN -180.0 AND 180.0)
                )
            );

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

            INSERT OR IGNORE INTO trips (
                trip_id, origin_name, origin_latitude, origin_longitude,
                destination_name, destination_latitude, destination_longitude,
                status, started_at, ended_at, created_at, updated_at
            )
            SELECT
                trip_id, '위치 정보 없음', NULL, NULL,
                NULL, NULL, NULL,
                'active', MIN(created_at), NULL, MIN(created_at), MAX(updated_at)
            FROM stories
            GROUP BY trip_id;
            """
        )


def trip_row_to_dict(row: sqlite3.Row) -> dict:
    return {
        "id": row["trip_id"],
        "origin_name": row["origin_name"],
        "origin_latitude": row["origin_latitude"],
        "origin_longitude": row["origin_longitude"],
        "destination_name": row["destination_name"],
        "destination_latitude": row["destination_latitude"],
        "destination_longitude": row["destination_longitude"],
        "status": row["status"],
        "started_at": row["started_at"],
        "ended_at": row["ended_at"],
        "story_count": row["story_count"],
    }


def create_trip(
    origin_name: str,
    origin_latitude: Optional[float] = None,
    origin_longitude: Optional[float] = None,
    destination_name: Optional[str] = None,
    destination_latitude: Optional[float] = None,
    destination_longitude: Optional[float] = None,
    database_path: Path = DATABASE_PATH,
) -> dict:
    """새 여행을 만들고 서버가 발급한 여행 ID를 반환한다."""
    trip_id = uuid4().hex
    now = datetime.now(timezone.utc).isoformat(timespec="microseconds")
    origin_name = origin_name.strip() or "위치 정보 없음"
    destination_name = destination_name.strip() if destination_name else None

    with connect_database(database_path) as connection:
        connection.execute(
            """
            INSERT INTO trips (
                trip_id, origin_name, origin_latitude, origin_longitude,
                destination_name, destination_latitude, destination_longitude,
                status, started_at, ended_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, NULL, ?, ?)
            """,
            (
                trip_id,
                origin_name,
                origin_latitude,
                origin_longitude,
                destination_name,
                destination_latitude,
                destination_longitude,
                now,
                now,
                now,
            ),
        )

    return get_trip(trip_id, database_path)


def ensure_trip(
    trip_id: str,
    origin_name: str = "위치 정보 없음",
    origin_latitude: Optional[float] = None,
    origin_longitude: Optional[float] = None,
    destination_name: Optional[str] = None,
    destination_latitude: Optional[float] = None,
    destination_longitude: Optional[float] = None,
    database_path: Path = DATABASE_PATH,
) -> dict:
    """현재 Android가 전달한 여행 ID가 DB에 없으면 기본 여행으로 등록한다."""
    now = datetime.now(timezone.utc).isoformat(timespec="microseconds")
    origin_name = origin_name.strip() or "위치 정보 없음"
    destination_name = destination_name.strip() if destination_name else None

    with connect_database(database_path) as connection:
        connection.execute(
            """
            INSERT INTO trips (
                trip_id, origin_name, origin_latitude, origin_longitude,
                destination_name, destination_latitude, destination_longitude,
                status, started_at, ended_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, NULL, ?, ?)
            ON CONFLICT (trip_id) DO UPDATE SET
                origin_name = CASE
                    WHEN trips.origin_name = '위치 정보 없음'
                        AND excluded.origin_name != '위치 정보 없음'
                    THEN excluded.origin_name
                    ELSE trips.origin_name
                END,
                origin_latitude = COALESCE(trips.origin_latitude, excluded.origin_latitude),
                origin_longitude = COALESCE(trips.origin_longitude, excluded.origin_longitude),
                destination_name = COALESCE(excluded.destination_name, trips.destination_name),
                destination_latitude = COALESCE(excluded.destination_latitude, trips.destination_latitude),
                destination_longitude = COALESCE(excluded.destination_longitude, trips.destination_longitude),
                updated_at = excluded.updated_at
            """,
            (
                trip_id,
                origin_name,
                origin_latitude,
                origin_longitude,
                destination_name,
                destination_latitude,
                destination_longitude,
                now,
                now,
                now,
            ),
        )

    return get_trip(trip_id, database_path)


def get_trip(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> Optional[dict]:
    """여행 기본 정보와 사용자가 남긴 이야기 수를 조회한다."""
    with connect_database(database_path) as connection:
        row = connection.execute(
            """
            SELECT
                trips.*,
                COUNT(saved_stories.story_id) AS story_count
            FROM trips
            LEFT JOIN saved_stories ON saved_stories.trip_id = trips.trip_id
            WHERE trips.trip_id = ?
            GROUP BY trips.trip_id
            """,
            (trip_id,),
        ).fetchone()

    return trip_row_to_dict(row) if row is not None else None


def list_recent_trips(
    limit: int = 6,
    database_path: Path = DATABASE_PATH,
) -> list[dict]:
    """최근 사용한 여행을 최신순으로 반환한다."""
    with connect_database(database_path) as connection:
        rows = connection.execute(
            """
            SELECT
                trips.*,
                COUNT(saved_stories.story_id) AS story_count
            FROM trips
            LEFT JOIN saved_stories ON saved_stories.trip_id = trips.trip_id
            GROUP BY trips.trip_id
            ORDER BY trips.updated_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()

    return [trip_row_to_dict(row) for row in rows]


def resume_trip(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> Optional[dict]:
    """기존 여행을 다시 기록 가능한 상태로 바꾼다."""
    now = datetime.now(timezone.utc).isoformat(timespec="microseconds")
    with connect_database(database_path) as connection:
        cursor = connection.execute(
            """
            UPDATE trips
            SET status = 'active', ended_at = NULL, updated_at = ?
            WHERE trip_id = ?
            """,
            (now, trip_id),
        )

    if cursor.rowcount == 0:
        return None
    return get_trip(trip_id, database_path)


def mark_trip_finished(
    trip_id: str,
    database_path: Path = DATABASE_PATH,
) -> Optional[dict]:
    """여행을 종료 상태로 바꾸고 종료 시각을 남긴다."""
    now = datetime.now(timezone.utc).isoformat(timespec="microseconds")
    with connect_database(database_path) as connection:
        cursor = connection.execute(
            """
            UPDATE trips
            SET status = 'finished', ended_at = ?, updated_at = ?
            WHERE trip_id = ?
            """,
            (now, now, trip_id),
        )

    if cursor.rowcount == 0:
        return None
    return get_trip(trip_id, database_path)


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
    ensure_trip(trip_id, database_path=database_path)
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


def get_story_map(database_path: Path = DATABASE_PATH) -> dict:
    """사용자가 남긴 모든 이야기를 전국 지도 화면 형식으로 반환한다."""
    with connect_database(database_path) as connection:
        rows = connection.execute(
            """
            SELECT
                stories.trip_id, stories.story_id, stories.title, stories.region,
                stories.latitude, stories.longitude, stories.summary,
                stories.highlights_json, stories.recorded_at, stories.created_at
            FROM saved_stories
            JOIN stories
                ON stories.trip_id = saved_stories.trip_id
                AND stories.story_id = saved_stories.story_id
            ORDER BY COALESCE(stories.recorded_at, stories.created_at) DESC
            """
        ).fetchall()

    stories = []
    trip_ids = set()
    region_totals = {}

    for row in rows:
        trip_ids.add(row["trip_id"])
        stories.append(
            {
                "id": row["story_id"],
                "trip_id": row["trip_id"],
                "title": row["title"],
                "region": row["region"],
                "latitude": row["latitude"],
                "longitude": row["longitude"],
                "summary": row["summary"],
                "highlights": json.loads(row["highlights_json"]),
                "recordedAt": row["recorded_at"],
            }
        )

        region = region_totals.setdefault(
            row["region"],
            {
                "story_count": 0,
                "latitude_sum": 0.0,
                "longitude_sum": 0.0,
                "coordinate_count": 0,
            },
        )
        region["story_count"] += 1
        if row["latitude"] is not None and row["longitude"] is not None:
            region["latitude_sum"] += row["latitude"]
            region["longitude_sum"] += row["longitude"]
            region["coordinate_count"] += 1

    regions = []
    for region_name, values in region_totals.items():
        coordinate_count = values["coordinate_count"]
        regions.append(
            {
                "region": region_name,
                "story_count": values["story_count"],
                "latitude": (
                    values["latitude_sum"] / coordinate_count
                    if coordinate_count
                    else None
                ),
                "longitude": (
                    values["longitude_sum"] / coordinate_count
                    if coordinate_count
                    else None
                ),
            }
        )

    regions.sort(key=lambda item: (-item["story_count"], item["region"]))
    return {
        "story_count": len(stories),
        "trip_count": len(trip_ids),
        "regions": regions,
        "stories": stories,
    }


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
