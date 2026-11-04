"""Validate and convert an OFFLINE backup into a new Linux deployment copy.

Never edits the source. Refuses overwrite, missing/colliding files or an empty DB.
Only files.path is rewritten. IDs, identities, messages and passwords are preserved.
"""
import argparse
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import sqlite3
from contextlib import closing


def convert(source, target, uploads, linux_uploads):
    source, target, uploads = Path(source).resolve(), Path(target).resolve(), Path(uploads).resolve()
    if not source.is_file() or not uploads.is_dir():
        raise ValueError("Source backup and copied uploads directory must exist")
    if target.exists() or target == source:
        raise ValueError("Target must be a new file, never the source or an existing database")
    prefix = PurePosixPath(linux_uploads)
    if not prefix.is_absolute():
        raise ValueError("Linux uploads directory must be absolute")
    # Read-only: this is a consistent OFFLINE backup, not a live DB with WAL writers.
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
        if original.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("Source database failed integrity_check")
        counts = {table: original.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0]
                  for table in ("users", "rooms", "files", "cdr", "private_messages", "room_messages")}
        if counts["users"] == 0:
            raise ValueError("Empty user table: refuse to replace real data with demo accounts")
        if counts["rooms"] == 0:
            raise ValueError("Empty room table triggers demo initialization; agree a startup policy before migration")
        changes, seen = [], {}
        for file_id, old_path, stored_size in original.execute("SELECT id,path,size FROM files"):
            name = PureWindowsPath(old_path).name if "\\" in old_path else PurePosixPath(old_path).name
            if not name or name in (".", ".."):
                raise ValueError(f"Invalid filename at file id {file_id}")
            if name in seen and seen[name] != old_path:
                raise ValueError(f"Filename collision at file id {file_id}; manually resolve before migration")
            seen[name] = old_path
            candidate = (uploads / name).resolve()
            if candidate.parent != uploads or not candidate.is_file():
                raise ValueError(f"Missing or unsafe upload for file id {file_id}")
            if candidate.stat().st_size != stored_size:
                raise ValueError(f"File size mismatch at file id {file_id}")
            changes.append((str(prefix / name), file_id))
        target.parent.mkdir(parents=True, exist_ok=True)
        # Reserve a new output so an existing file cannot be overwritten.
        with target.open("xb"):
            pass
        try:
            with closing(sqlite3.connect(target)) as migrated:
                original.backup(migrated)
                migrated.executemany("UPDATE files SET path=? WHERE id=?", changes)
                migrated.commit()
                if migrated.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise ValueError("Output failed integrity_check")
                if migrated.execute("PRAGMA foreign_key_check").fetchall():
                    raise ValueError("Output failed foreign_key_check")
                for table, count in counts.items():
                    if migrated.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0] != count:
                        raise ValueError("Unexpected record count change")
        except Exception:
            target.unlink(missing_ok=True)  # Only our newly reserved output, never the source.
            raise
    print(json.dumps({"counts": counts, "file_paths_rewritten": len(changes),
                      "source_unchanged": True, "target": str(target)}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--uploads", required=True)
    parser.add_argument("--linux-uploads", default="/srv/sipim/data/uploads")
    args = parser.parse_args()
    convert(args.source, args.target, args.uploads, args.linux_uploads)
