"""The evening laser show.

Runs as a background track alongside the kinetic sequences: it opens the
projection door, powers the lasers, runs the external show program and then
always shuts everything down again, even if the show program fails to start.
"""

from __future__ import annotations

import logging
import random
import subprocess
import time
from collections.abc import Generator
from pathlib import Path

from .config import LaserConfig
from .model import LASERS, PROJECTION_DOOR, PROJECTION_DOOR_POWER, Totem

log = logging.getLogger(__name__)


def pick_midi_file(midi_dir: str, rng: random.Random) -> Path | None:
    folder = Path(midi_dir)
    if not folder.is_dir():
        log.error("laser show midi folder %s does not exist", folder)
        return None
    files = sorted(
        p for p in folder.iterdir() if p.is_file() and p.name.startswith("midi") and p.name.endswith("txt")
    )
    if not files:
        log.error("no midi*.txt files in %s", folder)
        return None
    return rng.choice(files)


def build_command(cfg: LaserConfig, rng: random.Random) -> list[str] | None:
    if not cfg.command:
        log.warning("laser show has no command configured; running door/laser cycle only")
        return None
    if any("{midi}" in part for part in cfg.command):
        midi = pick_midi_file(cfg.midi_dir, rng)
        if midi is None:
            return None
        return [part.replace("{midi}", str(midi)) for part in cfg.command]
    return list(cfg.command)


def laser_show(totem: Totem, cfg: LaserConfig, rng: random.Random) -> Generator[float, None, None]:
    log.info("laser show starting")
    totem.set_control(PROJECTION_DOOR_POWER, True)
    yield 1.0
    totem.set_control(PROJECTION_DOOR, True)
    yield 30.0
    totem.set_control(LASERS, True)
    yield 30.0

    command = build_command(cfg, rng)
    if command is not None:
        try:
            proc = subprocess.Popen(command, cwd=cfg.working_dir or None)
        except OSError:
            log.exception("could not start laser show program %s", command)
        else:
            deadline = time.monotonic() + cfg.max_show_minutes * 60
            while proc.poll() is None:
                if time.monotonic() > deadline:
                    log.error("laser show program ran over %.0f minutes; stopping it", cfg.max_show_minutes)
                    proc.terminate()
                    try:
                        proc.wait(10)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                    break
                yield 1.0
            log.info("laser show program finished (exit code %s)", proc.returncode)

    yield 30.0
    totem.set_control(LASERS, False)
    yield 20.0
    totem.set_control(PROJECTION_DOOR, False)
    yield 30.0
    totem.set_control(PROJECTION_DOOR_POWER, False)
    log.info("laser show finished")
