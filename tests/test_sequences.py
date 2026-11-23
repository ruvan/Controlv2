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


def test_beaks_propagation_no_longer_aborts_after_one_step():
    # runBeaksPropogation ran one step of petal propagation then marked itself
    # finished, leaving petals stuck open. It is now petal propagation proper.
    assert "beaks_propagation" not in sequences.REGISTRY
    assert "petal_propagation" in sequences.REACTIONS


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
