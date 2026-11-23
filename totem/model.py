"""Physical model of the totem: relays, petals and flowers.

Relay layout (19 banks of 8 relays on the ProXR board; bank index 0 is board bank 1):

* Bank 0 is the control bank:
    relay 0 = projection door, relay 1 = lasers,
    relay 2 = 12V relay-coil supply, relay 3 = projection door power.
* Banks 1-18 drive petals.  Relay 0 of each is the bank's PSU, relays 1-6 are
  petals and relay 7 is unused.

The petals form three levels of twelve triangular flowers.  Each flower has
three petals; the bank a petal sits on encodes its orientation (2-7) and level,
and the relay column encodes the flower's position around the totem.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

NUM_BANKS = 19
RELAYS_PER_BANK = 8
LEVELS = 3
FLOWERS_PER_LEVEL = 12
PETAL_COLUMNS = range(1, 7)  # relays 1-6 of a petal bank carry petals
PETAL_BANKS = range(1, NUM_BANKS)

# Control bank relays
CONTROL_BANK = 0
PROJECTION_DOOR = 0
LASERS = 1
COIL_SUPPLY = 2
PROJECTION_DOOR_POWER = 3
PSU_RELAY = 0

# Petal orientations of "up" and "down" flowers, indexed by petal slot.
UP_PETALS = (5, 7, 3)
DOWN_PETALS = (4, 2, 6)


@dataclass
class Relay:
    state: bool = False
    strokes: int = 0
    last_changed: float = field(default_factory=time.monotonic)

    def set(self, state: bool) -> bool:
        """Set the relay. Returns True if it changed."""
        if state == self.state:
            return False
        self.state = state
        self.strokes += 1
        self.last_changed = time.monotonic()
        return True

    def toggle(self) -> None:
        self.set(not self.state)


@dataclass
class Petal:
    orientation: int
    slot: int
    flower: "Flower"
    relay: Relay


class Flower:
    def __init__(self, level: int, number: int, totem: "Totem"):
        self.level = level
        self.number = number
        self.up = (level + number) % 2 == 0
        orientations = UP_PETALS if self.up else DOWN_PETALS
        column = number // 2 + 1
        self.petals = tuple(
            Petal(o, slot, self, totem.relays[o + level * 6 - 1][column])
            for slot, o in enumerate(orientations)
        )

    def __repr__(self) -> str:
        return f"Flower(level={self.level}, number={self.number})"

    def set_all(self, state: bool) -> None:
        for p in self.petals:
            p.relay.set(state)

    def toggle(self) -> None:
        for p in self.petals:
            p.relay.toggle()

    @property
    def in_bloom(self) -> bool:
        return all(p.relay.state for p in self.petals)


class Totem:
    def __init__(self) -> None:
        self.relays = [[Relay() for _ in range(RELAYS_PER_BANK)] for _ in range(NUM_BANKS)]
        self.flowers = [
            [Flower(level, n, self) for n in range(FLOWERS_PER_LEVEL)] for level in range(LEVELS)
        ]

    # ---- iteration helpers -------------------------------------------------
    def all_flowers(self):
        for level in self.flowers:
            yield from level

    def all_petals(self):
        for flower in self.all_flowers():
            yield from flower.petals

    def flower(self, level: int, number: int) -> Flower:
        return self.flowers[level][number % FLOWERS_PER_LEVEL]

    # ---- geometry ----------------------------------------------------------
    def beak_partner(self, petal: Petal) -> Petal | None:
        """The petal on the neighbouring flower that shares an edge with this one."""
        f = petal.flower
        match petal.orientation:
            case 2:
                return self.flower(f.level, f.number + 1).petals[1]
            case 3:
                return self.flower(f.level, f.number + 1).petals[2]
            case 6:
                return self.flower(f.level, f.number - 1).petals[2]
            case 7:
                return self.flower(f.level, f.number - 1).petals[1]
            case 4:
                if f.level + 1 >= LEVELS:
                    return None
                return self.flowers[f.level + 1][f.number].petals[0]
            case 5:
                if f.level == 0:
                    return None
                return self.flowers[f.level - 1][f.number].petals[0]
        raise ValueError(f"bad orientation {petal.orientation}")

    def adjacent_petals(self, petal: Petal) -> list[Petal]:
        """The other two petals of the same flower plus the beak partner, if any."""
        adjacent = [p for p in petal.flower.petals if p is not petal]
        partner = self.beak_partner(petal)
        if partner is not None:
            adjacent.append(partner)
        return adjacent

    def adjacent_flowers(self, flower: Flower) -> list[Flower]:
        """Neighbours around the ring plus the flower above or below, if any."""
        adjacent = [
            self.flower(flower.level, flower.number + 1),
            self.flower(flower.level, flower.number - 1),
        ]
        vertical = self.beak_partner(flower.petals[0])
        if vertical is not None:
            adjacent.append(vertical.flower)
        return adjacent

    # ---- bank operations ---------------------------------------------------
    def bank_value(self, bank: int) -> int:
        return sum(1 << i for i, r in enumerate(self.relays[bank]) if r.state)

    def bank_values(self) -> list[int]:
        return [self.bank_value(b) for b in range(NUM_BANKS)]

    def set_bank_value(self, bank: int, value: int) -> None:
        """Set all 8 relays of a bank from a byte; bit i drives relay i."""
        if not 0 <= value <= 255:
            raise ValueError(f"bank value {value} out of range")
        for i, relay in enumerate(self.relays[bank]):
            relay.set(bool(value >> i & 1))

    def set_petal_bank(self, bank: int, state: bool) -> None:
        for col in PETAL_COLUMNS:
            self.relays[bank][col].set(state)

    def toggle_petal_bank(self, bank: int) -> None:
        for col in PETAL_COLUMNS:
            self.relays[bank][col].toggle()

    def clear(self) -> None:
        """Close every petal (PSUs and the control bank are left alone)."""
        for bank in PETAL_BANKS:
            self.set_petal_bank(bank, False)

    def invert(self) -> None:
        for bank in PETAL_BANKS:
            self.toggle_petal_bank(bank)

    def set_psus(self, state: bool) -> None:
        for bank in PETAL_BANKS:
            self.relays[bank][PSU_RELAY].set(state)
        self.relays[CONTROL_BANK][COIL_SUPPLY].set(state)

    def set_control(self, relay: int, state: bool) -> None:
        self.relays[CONTROL_BANK][relay].set(state)
