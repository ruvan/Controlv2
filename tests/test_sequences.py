import random

import pytest

from totem import sequences
from totem.config import Config
from totem.model import Totem


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


def make_ctx(seed=1):
    cfg = Config()
    clock = FakeClock()
    ctx = sequences.Context(
        totem=Totem(), rng=random.Random(seed), schedule=cfg.schedule, reactions=cfg.reactions,
        clock=clock, sensors=lambda: [0] * 13,
    )
    return ctx, clock


def run(gen, clock, limit=100_000):
    steps = 0
    for delay in gen:
        clock.t += delay
        steps += 1
        assert steps < limit, "sequence never finished"
    return steps


@pytest.mark.parametrize("name", [n for n in sequences.REGISTRY if n != "input_test"])
def test_every_sequence_finishes_with_default_params(name):
    # In the Java version, starting e.g. chaos/half/checkerboard/propagation from
    # the web panel crashed the relay thread (NullPointerException on the params map).
    ctx, clock = make_ctx()
    ctx.totem.set_psus(True)
    run(sequences.create(name, ctx), clock)


@pytest.mark.parametrize("name", ["petal_propagation", "bloom_propagation"])
@pytest.mark.parametrize("seed", range(5))
def test_propagation_reaches_everything_and_returns_to_start(name, seed):
    ctx, clock = make_ctx(seed)
    before = ctx.totem.bank_values()
    gen = sequences.create(name, ctx, {"sensors": [0, 3]})
    run(gen, clock)
    assert ctx.totem.bank_values() == before
    # every petal was moved (each petal stroke count is even and > 0)
    assert all(p.relay.strokes >= 2 and p.relay.strokes % 2 == 0 for p in ctx.totem.all_petals())


def test_beaks_propagation_ripples_whole_beaks():
    # runBeaksPropogation was unfinished: it ran one step of petal propagation
    # and marked itself finished, leaving petals stuck open.
    ctx, clock = make_ctx()
    units = sequences.beaks(ctx.totem)
    assert sorted(len(u) for u in units).count(1) == 12  # outer edges
    assert sum(len(u) for u in units) == 108
    gen = sequences.create("beaks_propagation", ctx, {"sensors": [1]})
    next(gen)
    for unit in units:  # both petals of a beak always move together
        assert len({p.relay.state for p in unit}) == 1
    run(gen, clock)
    assert not any(p.relay.state for p in ctx.totem.all_petals())
    assert all(p.relay.strokes == 2 for p in ctx.totem.all_petals())


@pytest.mark.parametrize("tip", range(12))
def test_huge_triangle_is_a_solid_side_three_triangle(tip):
    ctx, clock = make_ctx()
    rows = sequences.big_triangle_flowers(ctx.totem, tip)
    assert [len(r) for r in rows] == [1, 3, 5]
    flowers = [f for r in rows for f in r]
    assert len({id(f) for f in flowers}) == 9
    # Tip and the outer flowers of each row point the same way as the big triangle.
    up = tip % 2 == 0
    assert rows[0][0].up is up
    assert all(r[0].up is up and r[-1].up is up for r in rows)
    # Flowers in a row are side by side, and every flower pointing the same way as
    # the big triangle sits on (or hangs from) a flower in the next row.
    for row in rows:
        for a, b in zip(row, row[1:]):
            assert b in ctx.totem.adjacent_flowers(a)
    for upper, lower in zip(rows, rows[1:]):
        for f in upper:
            if f.up is up:
                assert ctx.totem.beak_partner(f.petals[0]).flower in lower
    # It is drawn from the tip outwards and ends fully open.
    gen = sequences.create("huge_triangle", ctx, {"tip": tip})
    next(gen)
    assert rows[0][0].in_bloom and not any(f.in_bloom for f in rows[2])
    run(gen, clock)
    open_flowers = {id(f) for f in ctx.totem.all_flowers() if f.in_bloom}
    assert open_flowers == {id(f) for f in flowers}
    assert sum(p.relay.state for p in ctx.totem.all_petals()) == 27


def test_random_still_can_pick_every_option():
    # nextInt(numberOfStills - 1) meant "bands" was never chosen, and
    # dinosaur foot variant 3 / half orientation 7 were unreachable.
    rng = random.Random(0)
    names, halves, feet = set(), set(), set()
    for _ in range(5000):
        name, params = sequences.random_still(rng)
        names.add(name)
        if name == "half":
            halves.add(params["orientation"])
        if name == "dinosaur_foot":
            feet.add(params["variant"])
    assert "bands" in names
    assert halves == {2, 3, 4, 5, 6, 7}
    assert feet == {0, 1, 2, 3}


def test_bad_params_rejected():
    ctx, _ = make_ctx()
    with pytest.raises(ValueError):
        next(sequences.create("half", ctx, {"orientation": 9}))
    with pytest.raises(ValueError):
        next(sequences.create("petal_propagation", ctx, {"sensors": [7]}))


def test_diagonal_chase_ends_clear():
    ctx, clock = make_ctx()
    run(sequences.create("diagonal_chase", ctx), clock)
    assert not any(p.relay.state for p in ctx.totem.all_petals())


def test_turn_off_retracts_before_cutting_power():
    ctx, clock = make_ctx()
    ctx.totem.set_psus(True)
    ctx.totem.flowers[0][0].set_all(True)
    gen = sequences.create("turn_off", ctx)
    assert next(gen) >= 10
    assert not ctx.totem.flowers[0][0].in_bloom and ctx.totem.relays[1][0].state
    run(gen, clock)
    assert not ctx.totem.relays[1][0].state and not ctx.totem.relays[0][2].state
