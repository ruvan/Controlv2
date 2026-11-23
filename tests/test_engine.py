import datetime as dt
import random

import pytest

from totem.config import Config
from totem.engine import AUTO, MANUAL, Engine, dance_times, in_operating_hours
from totem.hardware import SimulatedRelayBoard, decode_sensor_reply


class Clocks:
    def __init__(self, start: dt.datetime):
        self.wall = start
        self.mono = 0.0

    def advance(self, seconds: float):
        self.wall += dt.timedelta(seconds=seconds)
        self.mono += seconds


def make_engine(start=dt.datetime(2026, 1, 5, 12, 0), **cfg_changes):
    cfg = Config()
    for key, value in cfg_changes.items():
        section, name = key.split("__")
        setattr(getattr(cfg, section), name, value)
    clocks = Clocks(start)
    board = SimulatedRelayBoard()
    eng = Engine(cfg, board, rng=random.Random(3), clock=lambda: clocks.mono, wall_clock=lambda: clocks.wall)
    eng.startup()
    return eng, board, clocks


def run_for(eng, clocks, seconds, step=0.1):
    for _ in range(int(seconds / step)):
        eng.tick()
        clocks.advance(step)


@pytest.mark.parametrize(
    "hour,start,end,expected",
    [(6, 7, 0, False), (7, 7, 0, True), (23, 7, 0, True), (0, 7, 0, False),
     (22, 20, 2, True), (1, 20, 2, True), (2, 20, 2, False), (12, 9, 17, True), (17, 9, 17, False)],
)
def test_operating_hours(hour, start, end, expected):
    assert in_operating_hours(hour, start, end) is expected


def test_operating_hours_end_before_midnight_does_not_restart():
    # With end_of_day=23 the Java loop switched off at 23:00 and straight back on
    # the next second (hour >= startOfDay). The engine must stay asleep.
    eng, board, clocks = make_engine(dt.datetime(2026, 1, 5, 22, 59, 50), schedule__end_of_day=23)
    run_for(eng, clocks, 5)
    assert eng.day_active
    run_for(eng, clocks, 30)
    assert not eng.day_active
    assert eng.current_hour == 23 and not eng.dances


def test_dance_times_span_day():
    rng = random.Random(1)
    times = dance_times(dt.datetime(2026, 1, 5, 7, 0), 7, 0, 20, 3, rng)
    assert times[0].hour == 7
    assert all(in_operating_hours(t.hour, 7, 0) for t in times)
    assert all(dt.timedelta(minutes=17) <= b - a <= dt.timedelta(minutes=23) for a, b in zip(times, times[1:]))
    assert times[-1].hour == 23


def test_startup_powers_off_then_on_in_hours():
    eng, board, clocks = make_engine()
    run_for(eng, clocks, 1)
    assert [j.name for j in eng.normal][:2] == ["turn_off", "turn_on"]
    run_for(eng, clocks, 25)
    assert eng.totem.relays[1][0].state  # PSU on
    assert board.banks == eng.totem.bank_values()


def test_end_of_day_powers_down_and_stops_reacting():
    eng, board, clocks = make_engine(dt.datetime(2026, 1, 5, 23, 59, 0))
    run_for(eng, clocks, 30)
    assert eng.totem.relays[1][0].state
    run_for(eng, clocks, 60)
    assert not eng.day_active
    assert not eng.totem.relays[1][0].state
    board.sensors[0] = 1000
    run_for(eng, clocks, 5)
    assert not any(j.reaction for j in eng.urgent)


def test_reaction_preempts_dance_and_dance_resumes():
    eng, board, clocks = make_engine()
    run_for(eng, clocks, 30)  # power up, first dance queued
    assert eng.normal
    board.sensors[2] = 1000
    run_for(eng, clocks, 1)
    board.sensors[2] = 0
    assert eng.urgent and eng.urgent[0].reaction
    normal_before = len(eng.normal)
    run_for(eng, clocks, 1)
    assert len(eng.normal) == normal_before  # dance paused, not dropped
    run_for(eng, clocks, 300)
    assert not eng.urgent
    assert eng.reactions_this_hour == 1


def test_reaction_limits():
    eng, board, clocks = make_engine(reactions__per_hour=2, reactions__initial_cooldown_seconds=1.0)
    run_for(eng, clocks, 25)
    board.sensors[0] = 1000
    run_for(eng, clocks, 600)
    assert eng.reactions_this_hour == 2


def test_manual_sequence_runs_once_and_holds():
    eng, board, clocks = make_engine()
    run_for(eng, clocks, 30)
    eng.submit("sequence", "all_bloom")
    run_for(eng, clocks, 1)
    assert eng.mode == MANUAL
    run_for(eng, clocks, 60)
    assert eng.head() is None
    assert all(f.in_bloom for f in eng.totem.all_flowers())
    # dances are not started while in manual mode
    clocks.advance(3600)
    run_for(eng, clocks, 5)
    assert eng.head() is None


def test_auto_command_returns_to_automatic():
    # In the Java version "auto" compared the split String[] to "auto" and so
    # could never leave manual mode.
    eng, board, clocks = make_engine()
    run_for(eng, clocks, 30)
    eng.submit("sequence", "blank")
    run_for(eng, clocks, 15)
    eng.submit("auto")
    run_for(eng, clocks, 30)
    assert eng.mode == AUTO and eng.day_active
    assert eng.totem.relays[1][0].state


def test_override_sets_exact_bank_values():
    eng, board, clocks = make_engine()
    run_for(eng, clocks, 30)
    values = {i: 0 for i in range(19)}
    values[0] = 0b1010
    values[4] = 0b0000_0011
    eng.submit("override", values)
    run_for(eng, clocks, 1)
    assert board.banks[0] == 0b1010
    assert board.banks[4] == 0b11
    assert board.banks[5] == 0
    assert eng.mode == MANUAL and eng.head() is None


def test_bad_command_does_not_kill_engine():
    eng, board, clocks = make_engine()
    eng.submit("sequence", "no_such_thing")
    eng.submit("override", {0: 999})
    run_for(eng, clocks, 1)
    eng.submit("sequence", "blank")
    run_for(eng, clocks, 1)
    assert eng.mode == MANUAL


def test_laser_show_runs_once_and_always_shuts_down(tmp_path):
    eng, board, clocks = make_engine(
        dt.datetime(2026, 1, 5, 21, 14, 50),
        laser__enabled=True,
        laser__command=["/nonexistent/program", "{midi}"],
        laser__midi_dir=str(tmp_path),  # empty: no midi files
    )
    run_for(eng, clocks, 15)
    assert eng.background
    run_for(eng, clocks, 40)
    assert eng.totem.relays[0][0].state  # door open
    run_for(eng, clocks, 200)
    assert not eng.background
    assert not any(eng.totem.relays[0][r].state for r in (0, 1, 3))
    run_for(eng, clocks, 120)
    assert not eng.background  # not restarted the same evening


def test_laser_show_program_failure_still_shuts_down(tmp_path):
    (tmp_path / "midi1.txt").write_text("x")
    eng, board, clocks = make_engine(
        dt.datetime(2026, 1, 5, 21, 15, 0),
        laser__enabled=True,
        laser__command=["/nonexistent/program", "{midi}"],
        laser__midi_dir=str(tmp_path),
    )
    run_for(eng, clocks, 300)
    assert not eng.background
    assert not eng.totem.relays[0][1].state


def test_decode_sensor_reply():
    data = bytes([0]) + b"".join(bytes([v & 0xFF, v >> 8]) for v in range(0, 1300, 100))
    assert decode_sensor_reply(data) == list(range(0, 1300, 100))
    assert decode_sensor_reply(data[:-1]) is None
