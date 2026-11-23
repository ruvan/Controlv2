# Totem controller

Controls the Totem kinetic sculpture: 108 pneumatic petals (36 triangular
flowers on three levels) driven by an NCD ProXR relay board, six motion sensors,
weather sensors, and an evening laser show.

This is a Python rewrite of the original Java 7 controller and PHP control panel.

## Running

Requires Python 3.11+.

```sh
pip install .                      # installs pyserial and the `totem` command
cp config.example.toml config.toml # then edit: serial port, hours, passwords
totem config.toml                  # or: python -m totem config.toml
```

Run without hardware with `totem config.toml --simulate` (or set `port = "sim"`).

The control panel is served on `http://<host>:8080/` (configurable). The
admin password gives full control. The optional viewer password gives read-only
status. If no admin password is configured, a random one is printed in the log.

## How it behaves

* **Operating hours** (`start_of_day` to `end_of_day`, which may wrap past
  midnight): the totem powers up and dances roughly every 20 minutes. Each
  dance is ten random stills held for 10 s each.
* **Reactions**: when a motion sensor fires, a petal or bloom ripple spreads out
  from the flower above it. Reactions interrupt the current dance, which then
  resumes. They are rate-limited by a cooldown and a per-hour cap.
* **Outside hours**: all petals retract, then the power supplies switch off.
  The totem does not react to sensors.
* **Laser show**: at `laser.start_time` the projection door opens, the lasers
  power up, the show program runs, and everything is shut down afterwards
  (also if the show program fails or overruns).
* **Manual mode**: setting relays or running a sequence from the panel pauses all
  automatic behaviour until you press *Return to automatic*.

## Layout

| Module | Purpose |
| --- | --- |
| `totem/model.py` | Relays, petals, flowers, and the geometry linking them |
| `totem/sequences.py` | All kinetic sequences, written as non-blocking generators |
| `totem/engine.py` | Control loop: schedule, queue, reactions, relay output |
| `totem/hardware.py` | ProXR serial protocol and a simulated board |
| `totem/laser.py` | Laser show |
| `totem/web.py`, `totem/static/` | Control panel |

Run the tests with `pip install .[test] && pytest`.
