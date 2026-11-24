"""Kinetic sequences.

Each sequence is a generator. It changes relay states on the totem and then
yields the number of seconds to wait before it should be resumed. The engine
pushes the relay states to the board after every step, so a sequence never
blocks the control loop (sensors keep being read and reactions can interrupt).
"""

from __future__ import annotations

import random
from collections.abc import Callable, Generator, Iterable
from dataclasses import dataclass
from typing import Any

from .config import ReactionConfig, ScheduleConfig
from .model import FLOWERS_PER_LEVEL, LEVELS, Totem

Step = Generator[float, None, None]


@dataclass
class Context:
    totem: Totem
    rng: random.Random
    schedule: ScheduleConfig
    reactions: ReactionConfig
    clock: Callable[[], float]
    sensors: Callable[[], list[int]]


SequenceFn = Callable[[Context, dict[str, Any]], Step]
REGISTRY: dict[str, tuple[str, SequenceFn]] = {}


def sequence(name: str, label: str):
    def register(fn: SequenceFn) -> SequenceFn:
        REGISTRY[name] = (label, fn)
        return fn

    return register


def _bool(params: dict, key: str, default: bool = False) -> bool:
    value = params.get(key, default)
    if not isinstance(value, bool):
        raise ValueError(f"{key} must be true/false")
    return value


def _int(params: dict, key: str, default: int, lo: int, hi: int) -> int:
    value = params.get(key, default)
    if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
        raise ValueError(f"{key} must be an integer between {lo} and {hi}")
    return value


def _sensor_list(params: dict) -> list[int]:
    sensors = params.get("sensors", [])
    if not isinstance(sensors, list) or not all(
        isinstance(s, int) and not isinstance(s, bool) and 0 <= s < 6 for s in sensors
    ):
        raise ValueError("sensors must be a list of motion sensor numbers 0-5")
    return sensors


def _toggle_banks(ctx: Context, params: dict, offsets: Iterable[int]) -> Step:
    """Toggle the given bank offsets on every level, then hold."""
    if not _bool(params, "add"):
        ctx.totem.clear()
    for level in range(LEVELS):
        for offset in offsets:
            ctx.totem.toggle_petal_bank(6 * level + offset)
    yield ctx.schedule.hold_seconds


# ---------------------------------------------------------------------------
# Power
# ---------------------------------------------------------------------------


@sequence("turn_on", "Power on")
def turn_on(ctx: Context, params: dict) -> Step:
    ctx.totem.set_psus(True)
    yield ctx.schedule.hold_seconds


@sequence("turn_off", "Power off")
def turn_off(ctx: Context, params: dict) -> Step:
    ctx.totem.clear()  # pull in all actuators first ...
    yield 10.0
    ctx.totem.set_psus(False)  # ... then cut the power supplies


# ---------------------------------------------------------------------------
# Stills (one pattern held for hold_seconds)
# ---------------------------------------------------------------------------


@sequence("right_diagonals", "Right diagonals")
def right_diagonals(ctx: Context, params: dict) -> Step:
    yield from _toggle_banks(ctx, params, (1, 6))


@sequence("left_diagonals", "Left diagonals")
def left_diagonals(ctx: Context, params: dict) -> Step:
    yield from _toggle_banks(ctx, params, (2, 5))


@sequence("horizontals", "Horizontals")
def horizontals(ctx: Context, params: dict) -> Step:
    yield from _toggle_banks(ctx, params, (3, 4))


@sequence("both_diagonals", "Both diagonals")
def both_diagonals(ctx: Context, params: dict) -> Step:
    yield from _toggle_banks(ctx, params, (1, 6, 2, 5))


@sequence("left_diagonals_and_horizontals", "Left diagonals + horizontals")
def left_d_and_h(ctx: Context, params: dict) -> Step:
    yield from _toggle_banks(ctx, params, (3, 4, 2, 5))


@sequence("right_diagonals_and_horizontals", "Right diagonals + horizontals")
def right_d_and_h(ctx: Context, params: dict) -> Step:
    yield from _toggle_banks(ctx, params, (3, 4, 1, 6))


@sequence("blank", "Blank")
def blank(ctx: Context, params: dict) -> Step:
    ctx.totem.clear()
    yield ctx.schedule.hold_seconds


def big_triangle_flowers(totem: Totem, tip: int) -> list[list]:
    """The nine flowers forming a side-3 triangle, grouped in rows from its tip.

    Level 0 is the bottom row and level 2 the top. An even `tip` gives an upright
    triangle whose apex is flower `tip` on the top level. An odd `tip` gives an
    inverted triangle whose apex is flower `tip` on the bottom level.
    """
    levels = (2, 1, 0) if tip % 2 == 0 else (0, 1, 2)
    return [
        [totem.flower(level, tip + offset) for offset in range(-row, row + 1)]
        for row, level in enumerate(levels)
    ]


@sequence("huge_triangle", "Huge triangle")
def huge_triangle(ctx: Context, params: dict) -> Step:
    """Grow one big triangle row by row from its tip, then hold it."""
    tip = _int(params, "tip", ctx.rng.randrange(FLOWERS_PER_LEVEL), 0, FLOWERS_PER_LEVEL - 1)
    ctx.totem.clear()
    rows = big_triangle_flowers(ctx.totem, tip)
    for row in rows[:-1]:
        for flower in row:
            flower.set_all(True)
        yield ctx.schedule.step_seconds
    for flower in rows[-1]:
        flower.set_all(True)
    yield ctx.schedule.hold_seconds


@sequence("all_bloom", "All bloom")
def all_bloom(ctx: Context, params: dict) -> Step:
    for flower in ctx.totem.all_flowers():
        flower.set_all(True)
    yield ctx.schedule.hold_seconds


@sequence("stripes", "Stripes")
def stripes(ctx: Context, params: dict) -> Step:
    if not _bool(params, "add"):
        ctx.totem.clear()
    for n in range(FLOWERS_PER_LEVEL):
        if ctx.rng.random() < 0.25:
            for level in range(LEVELS):
                ctx.totem.flowers[level][n].toggle()
    yield ctx.schedule.hold_seconds


@sequence("bands", "Bands")
def bands(ctx: Context, params: dict) -> Step:
    if not _bool(params, "add"):
        ctx.totem.clear()
    for level in range(LEVELS):
        if ctx.rng.random() < 0.5:
            for offset in range(1, 7):
                ctx.totem.toggle_petal_bank(level * 6 + offset)
    yield ctx.schedule.hold_seconds


@sequence("chaos", "Chaos")
def chaos(ctx: Context, params: dict) -> Step:
    chaos_type = _int(params, "chaos_type", 1, 1, 3)
    if not _bool(params, "add"):
        ctx.totem.clear()
    for flower in ctx.totem.all_flowers():
        if chaos_type == 1:
            for petal in flower.petals:
                if ctx.rng.random() < 0.25:
                    petal.relay.toggle()
        elif chaos_type == 2:
            for petal in flower.petals:
                if ctx.rng.random() < 0.125:
                    petal.relay.toggle()
                    partner = ctx.totem.beak_partner(petal)
                    if partner is not None:
                        partner.relay.toggle()
        elif ctx.rng.random() < 0.25:
            flower.toggle()
    yield ctx.schedule.hold_seconds


@sequence("checkerboard", "Checkerboard")
def checkerboard(ctx: Context, params: dict) -> Step:
    inverted = _bool(params, "orientation")
    ctx.totem.clear()
    for flower in ctx.totem.all_flowers():
        if flower.level % 2 == flower.number % 2:
            flower.set_all(True)
    if inverted:
        ctx.totem.invert()
    yield ctx.schedule.hold_seconds


@sequence("half", "Half")
def half(ctx: Context, params: dict) -> Step:
    orientation = _int(params, "orientation", 2, 2, 7)
    if not _bool(params, "add"):
        ctx.totem.clear()
    for level in range(LEVELS):
        ctx.totem.toggle_petal_bank(level * 6 + orientation - 1)
    yield ctx.schedule.hold_seconds


DINOSAUR_FEET = ((2, 3), (5, 2), (6, 7), (4, 7))


@sequence("dinosaur_foot", "Dinosaur foot")
def dinosaur_foot(ctx: Context, params: dict) -> Step:
    variant = _int(params, "variant", 0, 0, len(DINOSAUR_FEET) - 1)
    first, second = DINOSAUR_FEET[variant]
    ctx.totem.clear()
    for level in range(LEVELS):
        ctx.totem.toggle_petal_bank(level * 6 + first - 1)
    yield ctx.schedule.step_seconds
    for level in range(LEVELS):
        ctx.totem.toggle_petal_bank(level * 6 + second - 1)
    yield ctx.schedule.hold_seconds


# ---------------------------------------------------------------------------
# Multi-step sequences
# ---------------------------------------------------------------------------


@sequence("diagonal_chase", "Diagonal chase")
def diagonal_chase(ctx: Context, params: dict) -> Step:
    t = ctx.totem
    previous: tuple[int, ...] = ()
    for offsets in ((1, 6), (5, 2), (4, 3)):
        for level in range(LEVELS):
            for o in previous:
                t.set_petal_bank(6 * level + o, False)
            for o in offsets:
                t.set_petal_bank(6 * level + o, True)
        previous = offsets
        yield ctx.schedule.step_seconds
    for level in range(LEVELS):
        for o in previous:
            t.set_petal_bank(6 * level + o, False)
    yield ctx.schedule.hold_seconds


@sequence("all_on_off", "All on / off")
def all_on_off(ctx: Context, params: dict) -> Step:
    for state in (False, True, False):
        for flower in ctx.totem.all_flowers():
            flower.set_all(state)
        yield ctx.schedule.hold_seconds


@sequence("flower_chase", "Flower chase")
def flower_chase(ctx: Context, params: dict) -> Step:
    for flower in ctx.totem.all_flowers():
        flower.set_all(True)
        yield ctx.schedule.step_seconds
    yield ctx.schedule.hold_seconds


@sequence("input_test", "Input test (runs until replaced)")
def input_test(ctx: Context, params: dict) -> Step:
    """Open the column of flowers above each motion sensor while it is triggered."""
    threshold = ctx.reactions.input_test_threshold
    while True:
        readings = ctx.sensors()
        for i in range(6):
            triggered = readings[i] > threshold
            for level in range(LEVELS):
                ctx.totem.flowers[level][i * 2 + 1].set_all(triggered)
        yield 0.1


# ---------------------------------------------------------------------------
# Reactions to the motion sensors
# ---------------------------------------------------------------------------


def _ripple(ctx: Context, units: list[tuple], neighbours: dict[int, list[int]], seeds: list[int]) -> Step:
    """Toggle the seed units, then ripple outwards.

    A unit is a group of petals moved together. Each active unit activates its
    idle neighbours after propagate_after_seconds and toggles back after
    revert_after_seconds. Every unit moves at most once.
    """
    rc = ctx.reactions
    active: dict[int, float] = {}
    done: set[int] = set()

    def activate(unit: int, now: float) -> None:
        for petal in units[unit]:
            petal.relay.toggle()
        active[unit] = now

    now = ctx.clock()
    for unit in seeds:
        if unit not in active:
            activate(unit, now)

    while active:
        yield 0.25
        now = ctx.clock()
        for unit, since in list(active.items()):
            if now >= since + rc.propagate_after_seconds:
                for n in neighbours[unit]:
                    if n not in active and n not in done:
                        activate(n, now)
            if now >= since + rc.revert_after_seconds:
                for petal in units[unit]:
                    petal.relay.toggle()
                del active[unit]
                done.add(unit)
    yield ctx.schedule.hold_seconds


@sequence("petal_propagation", "Petal propagation")
def petal_propagation(ctx: Context, params: dict) -> Step:
    """Toggle petals above the triggered sensors and ripple outwards petal by petal."""
    sensors = _sensor_list(params) or [ctx.rng.randrange(6)]
    petals = list(ctx.totem.all_petals())
    index = {id(p): i for i, p in enumerate(petals)}
    units = [(p,) for p in petals]
    neighbours = {i: [index[id(n)] for n in ctx.totem.adjacent_petals(p)] for i, p in enumerate(petals)}
    seeds = []
    for sensor in sensors:
        flower = ctx.totem.flowers[0][sensor * 2]
        chosen = [p for p in flower.petals if ctx.rng.random() < 0.5] or [flower.petals[0]]
        seeds.extend(index[id(p)] for p in chosen)
    yield from _ripple(ctx, units, neighbours, seeds)


def beaks(totem: Totem) -> list[tuple]:
    """Pairs of petals meeting along a shared edge (a "beak"); outer-edge petals stand alone."""
    seen: set[int] = set()
    result = []
    for petal in totem.all_petals():
        if id(petal) in seen:
            continue
        partner = totem.beak_partner(petal)
        group = (petal,) if partner is None else (petal, partner)
        seen.update(id(p) for p in group)
        result.append(group)
    return result


@sequence("beaks_propagation", "Beaks propagation")
def beaks_propagation(ctx: Context, params: dict) -> Step:
    """Snap beaks (both petals on an edge) shut and open, rippling from beak to beak."""
    sensors = _sensor_list(params) or [ctx.rng.randrange(6)]
    units = beaks(ctx.totem)
    by_flower: dict[int, list[int]] = {}
    for i, unit in enumerate(units):
        for petal in unit:
            by_flower.setdefault(id(petal.flower), []).append(i)
    neighbours = {
        i: sorted({n for p in unit for n in by_flower[id(p.flower)] if n != i}) for i, unit in enumerate(units)
    }
    seeds = sorted({i for s in sensors for i in by_flower[id(ctx.totem.flowers[0][s * 2])]})
    yield from _ripple(ctx, units, neighbours, seeds)


@sequence("bloom_propagation", "Bloom propagation")
def bloom_propagation(ctx: Context, params: dict) -> Step:
    """Toggle whole flowers above the triggered sensors and ripple outwards flower by flower."""
    sensors = _sensor_list(params) or [ctx.rng.randrange(6)]
    rc = ctx.reactions
    flowers = {id(f): f for f in ctx.totem.all_flowers()}
    active: dict[int, float] = {}
    reverted: dict[int, float] = {}

    now = ctx.clock()
    for sensor in sensors:
        flower = ctx.totem.flowers[0][sensor * 2]
        if id(flower) not in active:
            flower.toggle()
            active[id(flower)] = now

    while active:
        yield 0.25
        now = ctx.clock()
        for fid, since in list(active.items()):
            if fid not in active:
                continue  # reverted earlier in this pass
            flower = flowers[fid]
            if now >= since + rc.propagate_after_seconds:
                for neighbour in ctx.totem.adjacent_flowers(flower):
                    nid = id(neighbour)
                    if nid not in active and nid not in reverted:
                        neighbour.toggle()
                        active[nid] = now
            if now >= since + rc.revert_after_seconds:
                flower.toggle()
                del active[fid]
                reverted[fid] = now
        # A flower that closed a while ago pulls its still-open neighbours closed too.
        for fid, since in list(reverted.items()):
            if now >= since + rc.revert_after_seconds:
                for neighbour in ctx.totem.adjacent_flowers(flowers[fid]):
                    nid = id(neighbour)
                    if nid in active:
                        neighbour.toggle()
                        del active[nid]
                        reverted[nid] = now
    yield ctx.schedule.hold_seconds


# How the control panel groups the sequences.
GROUPS = {
    "Power": ("turn_on", "turn_off"),
    "Reactions": ("petal_propagation", "beaks_propagation", "bloom_propagation"),
    "Animated": ("diagonal_chase", "huge_triangle", "dinosaur_foot", "flower_chase", "all_on_off"),
    "Testing": ("input_test",),
}

REACTIONS = ("petal_propagation", "beaks_propagation", "bloom_propagation", "all_bloom")


# ---------------------------------------------------------------------------
# Random dances
# ---------------------------------------------------------------------------


def random_still(rng: random.Random) -> tuple[str, dict[str, Any]]:
    """Pick one of the stills a dance is made of, with random parameters."""

    def add() -> dict[str, Any]:
        return {"add": rng.random() < 0.25}

    choices: list[Callable[[], tuple[str, dict[str, Any]]]] = [
        lambda: ("right_diagonals", add()),
        lambda: ("left_diagonals", add()),
        lambda: ("horizontals", add()),
        lambda: ("both_diagonals", add()),
        lambda: ("left_diagonals_and_horizontals", add()),
        lambda: ("right_diagonals_and_horizontals", add()),
        lambda: ("blank", {}),
        lambda: ("huge_triangle", {"tip": rng.randrange(FLOWERS_PER_LEVEL)}),
        lambda: ("chaos", {**add(), "chaos_type": 1}),
        lambda: ("chaos", {**add(), "chaos_type": 2}),
        lambda: ("chaos", {**add(), "chaos_type": 3}),
        lambda: ("checkerboard", {"orientation": True}),
        lambda: ("checkerboard", {"orientation": False}),
        lambda: ("all_bloom", {}),
        lambda: ("half", {**add(), "orientation": rng.randint(2, 7)}),
        lambda: ("dinosaur_foot", {"variant": rng.randrange(len(DINOSAUR_FEET))}),
        lambda: ("stripes", add()),
        lambda: ("bands", add()),
    ]
    return rng.choice(choices)()


def create(name: str, ctx: Context, params: dict[str, Any] | None = None) -> Step:
    """Instantiate a sequence. Raises KeyError/ValueError on bad names or parameters."""
    params = params or {}
    _, fn = REGISTRY[name]
    gen = fn(ctx, params)
    return gen
