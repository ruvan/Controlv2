from totem.model import NUM_BANKS, PETAL_BANKS, PETAL_COLUMNS, Totem


def test_every_petal_has_its_own_relay_and_all_petal_relays_are_used():
    t = Totem()
    used = {id(p.relay) for p in t.all_petals()}
    assert len(used) == 3 * 12 * 3
    expected = {id(t.relays[b][c]) for b in PETAL_BANKS for c in PETAL_COLUMNS}
    assert used == expected


def test_beak_partners_are_symmetric():
    t = Totem()
    for petal in t.all_petals():
        partner = t.beak_partner(petal)
        if partner is not None:
            assert t.beak_partner(partner) is petal


def test_only_outer_edges_have_no_partner():
    t = Totem()
    missing = [p for p in t.all_petals() if t.beak_partner(p) is None]
    assert len(missing) == 12  # 6 at the bottom of level 0, 6 at the top of level 2
    assert all(p.flower.level in (0, 2) for p in missing)


def test_adjacent_flowers_are_symmetric_and_connected():
    t = Totem()
    for f in t.all_flowers():
        for n in t.adjacent_flowers(f):
            assert f in t.adjacent_flowers(n)
    seen, todo = set(), [t.flowers[0][0]]
    while todo:
        f = todo.pop()
        if id(f) not in seen:
            seen.add(id(f))
            todo.extend(t.adjacent_flowers(f))
    assert len(seen) == 36


def test_bank_value_round_trip_bit_order():
    # The Java version wrote Integer.toBinaryString MSB-first into relay 0,
    # so e.g. value 2 (relay 1) switched relay 0 instead.
    t = Totem()
    t.set_bank_value(3, 0b0000_0010)
    assert [r.state for r in t.relays[3]] == [False, True] + [False] * 6
    for v in (0, 1, 2, 0x81, 0xFF, 0x5A):
        t.set_bank_value(5, v)
        assert t.bank_value(5) == v


def test_set_bank_value_clears_unset_bits():
    t = Totem()
    t.set_bank_value(0, 0xFF)
    t.set_bank_value(0, 0x01)
    assert t.bank_value(0) == 0x01
    assert len(t.bank_values()) == NUM_BANKS


def test_clear_leaves_psus_and_control_bank():
    t = Totem()
    t.set_psus(True)
    t.set_control(1, True)
    for f in t.all_flowers():
        f.set_all(True)
    t.clear()
    assert not any(p.relay.state for p in t.all_petals())
    assert all(t.relays[b][0].state for b in PETAL_BANKS)
    assert t.relays[0][1].state
