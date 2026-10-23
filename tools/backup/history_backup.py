#!/usr/bin/env python3
"""进程外 SQLite 一致性备份；先排除游戏内原始文件备份并重启，再启用。"""

import argparse
from contextlib import closing
import fcntl
import logging
import os
from pathlib import Path
import shutil
import signal
import sqlite3
import tempfile
import time


def backup(world: Path, timeout: float = 240) -> None:
    """原子替换完整副本；发布前失败保留上一份，互斥锁覆盖备份和发布全过程。"""
    world = world.resolve(strict=True)
    source = world / "ae2-dashboard/history.sqlite"
    if not source.is_file():
        raise FileNotFoundError(f"历史数据库不存在: {source}")
    staging = world / ".ae2-dashboard-backup-staging"
    destination = world / "ae2-dashboard-backup"
    staging.mkdir(mode=0o700, exist_ok=True)
    destination.mkdir(mode=0o700, exist_ok=True)
    if staging.stat().st_dev != destination.stat().st_dev:
        raise OSError("暂存与发布目录必须位于同一文件系统")

    started = time.monotonic()
    deadline = started + timeout

    def check_deadline(*_):
        if time.monotonic() >= deadline:
            raise TimeoutError(f"备份超过 {timeout:g} 秒，上一份副本未替换")

    def alarm(_signum, _frame):
        raise TimeoutError(f"备份超过 {timeout:g} 秒，上一份副本未替换")

    # 不删除锁文件，避免其他进程锁住不同 inode 后同时进入。
    with (staging / "backup.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("已有备份正在运行，本次未执行") from error
        previous_handler = signal.signal(signal.SIGALRM, alarm)
        signal.setitimer(signal.ITIMER_REAL, timeout)
        temporary = None
        try:
            with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True, timeout=1)) as reader:
                reader.execute("PRAGMA query_only=ON")
                pages = reader.execute("PRAGMA page_count").fetchone()[0]
                page_size = reader.execute("PRAGMA page_size").fetchone()[0]
                required = pages * page_size + 64 * 1024 * 1024
                free = shutil.disk_usage(staging).free
                if free < required:
                    raise OSError(f"备份空间不足: free={free}, required={required}")
                handle, name = tempfile.mkstemp(prefix="history-", suffix=".sqlite", dir=staging)
                os.close(handle)
                temporary = Path(name)
                with closing(sqlite3.connect(temporary, timeout=1)) as writer:
                    reader.backup(writer, pages=256, progress=check_deadline, sleep=0.1)
                    # 完成副本不依赖 WAL/SHM，供世界 ZIP 单文件归档。
                    writer.execute("PRAGMA journal_mode=DELETE")

            # 关闭备份连接后检查独立文件，不再触碰在线库。
            with closing(sqlite3.connect(temporary.as_uri() + "?mode=ro&immutable=1", uri=True)) as verified:
                verified.set_progress_handler(lambda: int(time.monotonic() >= deadline), 10000)
                if verified.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                    raise sqlite3.DatabaseError("备份 quick_check 失败")
            check_deadline()
            size = temporary.stat().st_size
            with temporary.open("rb") as complete:
                os.fsync(complete.fileno())
            directory = os.open(destination, os.O_RDONLY | os.O_DIRECTORY)
            try:
                # 发布期间不再异步超时，避免成功替换后错误地报告保留了旧副本。
                signal.setitimer(signal.ITIMER_REAL, 0)
                os.replace(temporary, destination / "history.sqlite")
                try:
                    os.fsync(directory)
                except OSError as error:
                    raise OSError("新副本已完整发布，但目录同步失败；需检查磁盘持久性，旧副本已替换") from error
            finally:
                os.close(directory)
            logging.info("备份完成 bytes=%d elapsed_ms=%d", size, (time.monotonic() - started) * 1000)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, previous_handler)
            if temporary is not None:
                # 只清理本次生成的文件；中断遗留物留给运维核查。
                for suffix in ("", "-wal", "-shm", "-journal"):
                    Path(str(temporary) + suffix).unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=240)
    args = parser.parse_args()
    if not 0 < args.timeout <= 3600:
        parser.error("timeout 必须大于 0 且不超过 3600 秒")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        backup(args.world, args.timeout)
    except (OSError, sqlite3.Error, RuntimeError) as error:
        logging.error("备份失败: %s", error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
