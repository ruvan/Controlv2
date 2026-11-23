"""The control loop.

A single thread owns the relay board. Everything else (the web panel) talks to
it through a command queue and reads a status snapshot, so serial commands from
different sources can never interleave on the wire.
"""

from __future__ import annotations

import datetime as dt
import logging
import queue
import random
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from . import sequences
from .config import Config
from .hardware import NUM_SENSOR_CHANNELS, RelayBoard
from .laser import laser_show
from .model import NUM_BANKS, Totem

log = logging.getLogger(__name__)

AUTO = "auto"
MANUAL = "manual"
FULL_REFRESH_SECONDS = 60.0
TICK_SECONDS = 0.1
SENSOR_INTERVAL_SECONDS = 0.5


def in_operating_hours(hour: int, start: int, end: int) -> bool:
    """True if `hour` falls in [start, end), wrapping past midnight when end <= start."""
    if start == end:
        return True
    if start < end:
        return start <= hour < end
    return hour >= start or hour < end


def dance_times(
    now: dt.datetime, start: int, end: int, interval: int, jitter: int, rng: random.Random, limit: int = 150
) -> list[dt.datetime]:
    """Dance start times from now until the end of the operating day."""
    times = []
    t = now
    while in_operating_hours(t.hour, start, end) and len(times) < limit:
        times.append(t)
        t += dt.timedelta(minutes=interval + rng.randint(-jitter, jitter))
        if t - now > dt.timedelta(hours=24):
            break
    return times


@dataclass
class Job:
    name: str
    params: dict[str, Any] = field(default_factory=dict)
    reaction: bool = False
    gen: Any = None
    due: float = 0.0


class Engine:
    def __init__(
        self,
        cfg: Config,
        board: RelayBoard,
        *,
        rng: random.Random | None = None,
        clock: Callable[[], float] = time.monotonic,
        wall_clock: Callable[[], dt.datetime] = dt.datetime.now,
    ):
        self.cfg = cfg
        self.board = board
        self.rng = rng or random.Random()
        self.clock = clock
        self.wall_clock = wall_clock
        self.totem = Totem()
        self.sensors = [0] * NUM_SENSOR_CHANNELS
        self.ctx = sequences.Context(
            totem=self.totem,
            rng=self.rng,
            schedule=cfg.schedule,
            reactions=cfg.reactions,
            clock=clock,
            sensors=lambda: self.sensors,
        )

        self.mode = AUTO
        self.day_active = False
        self.urgent: deque[Job] = deque()  # reactions and manual requests
        self.normal: deque[Job] = deque()  # dances and power sequences
        self.dances: deque[dt.datetime] = deque()
        self.background: list[Job] = []  # laser show
        self.last_laser_date: dt.date | None = None

        self.last_reaction = -1e12
        self.reactions_this_hour = 0
        self.reaction_cooldown = cfg.reactions.initial_cooldown_seconds
        self.current_hour: int | None = None

        self._sent: list[int | None] = [None] * NUM_BANKS
        self._last_full_refresh = -1e12
        self._last_sensor_read = -1e12
        self.commands: queue.Queue[tuple[str, Any]] = queue.Queue()
        self._status: dict[str, Any] = {}
        self._status_lock = threading.Lock()
        self._stop = threading.Event()

    # ---- public API (thread-safe) -------------------------------------------
    def submit(self, command: str, arg: Any = None) -> None:
        self.commands.put((command, arg))

    def status(self) -> dict[str, Any]:
        with self._status_lock:
            return dict(self._status)

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        self.startup()
        while not self._stop.is_set():
            self.tick()
            self._stop.wait(TICK_SECONDS)

    # ---- control loop --------------------------------------------------------
    def startup(self) -> None:
        # Bring the hardware to a known state: everything retracted and powered down.
        self.enqueue(Job("turn_off"))

    def tick(self) -> None:
        now = self.clock()
        wall = self.wall_clock()
        self._process_commands()
        self._update_day(wall)
        self._update_dances(wall)
        self._update_laser(wall, now)
        self._read_sensors(now)
        self._maybe_react(now, wall)
        self._advance(now)
        self._flush(now)
        self._publish(wall)

    def enqueue(self, job: Job, urgent: bool = False) -> None:
        (self.urgent if urgent else self.normal).append(job)

    def clear_queue(self) -> None:
        for job in (*self.urgent, *self.normal):
            if job.gen is not None:
                job.gen.close()
        self.urgent.clear()
        self.normal.clear()

    def head(self) -> Job | None:
        if self.urgent:
            return self.urgent[0]
        if self.normal:
            return self.normal[0]
        return None

    def _process_commands(self) -> None:
        while True:
            try:
                command, arg = self.commands.get_nowait()
            except queue.Empty:
                return
            try:
                self._handle(command, arg)
            except Exception:
                log.exception("command %s failed", command)

    def _handle(self, command: str, arg: Any) -> None:
        if command == "override":
            # arg: {bank_index: value}; sets relays directly and holds them.
            self.mode = MANUAL
            self.clear_queue()
            for bank, value in arg.items():
                self.totem.set_bank_value(bank, value)
            log.info("manual relay override")
        elif command == "sequence":
            name = arg
            if name not in sequences.REGISTRY:
                raise ValueError(f"unknown sequence {name}")
            self.mode = MANUAL
            self.clear_queue()
            if not self.totem.relays[1][0].state:
                self.enqueue(Job("turn_on"), urgent=True)  # petals cannot move unpowered
            self.enqueue(Job(name), urgent=True)
            log.info("manual sequence %s", name)
        elif command == "auto":
            if self.mode != AUTO:
                log.info("returning to automatic mode")
                self.mode = AUTO
                self.clear_queue()
                # Power down; _update_day powers back up if we are within operating hours.
                self.day_active = False
                self.enqueue(Job("turn_off"))
        else:
            raise ValueError(f"unknown command {command}")

    def _update_day(self, wall: dt.datetime) -> None:
        if wall.hour != self.current_hour:
            if self.current_hour is not None:
                if self.reactions_this_hour:
                    log.info("resetting hourly reaction count")
                self.reactions_this_hour = 0
                self.reaction_cooldown = self.cfg.reactions.cooldown_seconds
            self.current_hour = wall.hour

        if self.mode != AUTO:
            return
        s = self.cfg.schedule
        active = in_operating_hours(wall.hour, s.start_of_day, s.end_of_day)
        if active and not self.day_active:
            log.info("start of day")
            self.day_active = True
            self.enqueue(Job("turn_on"))
            self.dances = deque(
                dance_times(
                    wall, s.start_of_day, s.end_of_day, s.dance_interval_minutes, s.dance_jitter_minutes, self.rng
                )
            )
            log.info("%d dances scheduled, last at %s", len(self.dances), self.dances[-1] if self.dances else "-")
        elif not active and self.day_active:
            log.info("end of day")
            self.day_active = False
            self.dances.clear()
            self.clear_queue()
            self.enqueue(Job("turn_off"))

    def _update_dances(self, wall: dt.datetime) -> None:
        while self.dances and wall >= self.dances[0]:
            self.dances.popleft()
            if self.mode != AUTO or not self.day_active:
                continue  # missed while in manual mode: skip rather than burst later
            log.info("starting new dance")
            for _ in range(self.cfg.schedule.dance_length):
                name, params = sequences.random_still(self.rng)
                self.enqueue(Job(name, params))

    def _update_laser(self, wall: dt.datetime, now: float) -> None:
        lc = self.cfg.laser
        if not lc.enabled or self.background or self.last_laser_date == wall.date():
            return
        start = dt.datetime.combine(wall.date(), lc.start_time, tzinfo=wall.tzinfo)
        if start <= wall < start + dt.timedelta(minutes=5):
            self.last_laser_date = wall.date()
            self.background.append(Job("laser_show", gen=laser_show(self.totem, lc, self.rng), due=now))

    def _read_sensors(self, now: float) -> None:
        if now < self._last_sensor_read + SENSOR_INTERVAL_SECONDS:
            return
        self._last_sensor_read = now
        try:
            values = self.board.read_sensors()
        except Exception:
            log.exception("sensor read failed")
            return
        if values is not None:
            self.sensors = values

    def _maybe_react(self, now: float, wall: dt.datetime) -> None:
        rc = self.cfg.reactions
        if not (rc.enabled and self.mode == AUTO and self.day_active):
            return
        if now < self.last_reaction + self.reaction_cooldown or self.reactions_this_hour >= rc.per_hour:
            return
        head = self.head()
        if head is not None and head.reaction:
            return
        triggered = [i for i in range(6) if self.sensors[i] > rc.motion_threshold]
        if not triggered:
            return
        self.last_reaction = now
        self.reactions_this_hour += 1
        name = self.rng.choice(sequences.REACTIONS)
        log.info("reacting to motion sensors %s with %s", triggered, name)
        self.enqueue(Job(name, {"sensors": triggered}, reaction=True), urgent=True)

    def _advance(self, now: float) -> None:
        for job in list(self.background):
            if now >= job.due and not self._step(job, now):
                self.background.remove(job)

        head = self.head()
        if head is None:
            return
        if head.gen is None:
            try:
                head.gen = sequences.create(head.name, self.ctx, head.params)
            except Exception:
                log.exception("cannot start sequence %s", head.name)
                self._pop(head)
                return
            head.due = now
            log.info("running %s %s", head.name, head.params or "")
        if now >= head.due and not self._step(head, now):
            self._pop(head)

    def _step(self, job: Job, now: float) -> bool:
        """Advance a job one step. Returns False once it has finished."""
        try:
            delay = next(job.gen)
        except StopIteration:
            return False
        except Exception:
            log.exception("sequence %s failed", job.name)
            return False
        job.due = now + delay
        return True

    def _pop(self, job: Job) -> None:
        for q in (self.urgent, self.normal):
            if q and q[0] is job:
                q.popleft()
                return

    def _flush(self, now: float) -> None:
        values = self.totem.bank_values()
        full = now >= self._last_full_refresh + FULL_REFRESH_SECONDS
        if full:
            self._last_full_refresh = now
        for bank, value in enumerate(values):
            if full or self._sent[bank] != value:
                try:
                    self.board.write_bank(bank, value)
                    self._sent[bank] = value
                except Exception:
                    log.exception("writing bank %d failed", bank + 1)
                    self._sent[bank] = None

    def _publish(self, wall: dt.datetime) -> None:
        head = self.head()
        status = {
            "time": wall.isoformat(timespec="seconds"),
            "mode": self.mode,
            "day_active": self.day_active,
            "mood": self.cfg.initial_mood,
            "current_sequence": head.name if head else None,
            "queue": [j.name for j in (*self.urgent, *self.normal)],
            "next_dance": self.dances[0].isoformat(timespec="minutes") if self.dances else None,
            "laser_show_running": bool(self.background),
            "reactions_this_hour": self.reactions_this_hour,
            "banks": self.totem.bank_values(),
            "sensors": list(self.sensors),
        }
        with self._status_lock:
            self._status = status
