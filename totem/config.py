"""Configuration loading (TOML)."""

from __future__ import annotations

import datetime as dt
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class RelayConfig:
    # Serial port name, e.g. "COM2" or "/dev/ttyUSB0". "sim" uses a simulated board.
    port: str = "sim"
    baud: int = 38400


@dataclass
class ScheduleConfig:
    start_of_day: int = 7  # hour the totem wakes up (0-23)
    end_of_day: int = 0  # hour the totem goes to sleep (0-23); may wrap past midnight
    dance_interval_minutes: int = 20
    dance_jitter_minutes: int = 3
    dance_length: int = 10  # number of stills per dance
    hold_seconds: float = 10.0  # how long each still is held
    step_seconds: float = 1.0  # time between steps of multi-step sequences


@dataclass
class ReactionConfig:
    enabled: bool = True
    motion_threshold: int = 254  # PIR reading above which a sensor counts as triggered
    input_test_threshold: int = 64
    initial_cooldown_seconds: float = 30.0  # cooldown until the first hourly reset
    cooldown_seconds: float = 60.0  # cooldown afterwards
    per_hour: int = 24
    propagate_after_seconds: float = 4.0
    revert_after_seconds: float = 14.0


@dataclass
class LaserConfig:
    enabled: bool = False
    start_time: dt.time = dt.time(21, 15)
    # Command to run the show. "{midi}" is replaced by a random midi*.txt file from midi_dir.
    command: list[str] = field(default_factory=list)
    midi_dir: str = ""
    working_dir: str = ""  # directory the show program runs in (default: current directory)
    max_show_minutes: float = 30.0


@dataclass
class WebConfig:
    enabled: bool = True
    host: str = "0.0.0.0"
    port: int = 8080
    admin_password: str = ""  # full control; a random one is generated and logged if empty
    viewer_password: str = ""  # read-only status; viewer login disabled if empty


@dataclass
class Config:
    program_name: str = "Totem"
    initial_mood: str = "Playful"
    relay: RelayConfig = field(default_factory=RelayConfig)
    schedule: ScheduleConfig = field(default_factory=ScheduleConfig)
    reactions: ReactionConfig = field(default_factory=ReactionConfig)
    laser: LaserConfig = field(default_factory=LaserConfig)
    web: WebConfig = field(default_factory=WebConfig)


class ConfigError(ValueError):
    pass


def _apply(target, values: dict, section: str) -> None:
    for key, value in values.items():
        if isinstance(value, dict):
            continue  # nested sections handled by the caller
        if not hasattr(target, key):
            raise ConfigError(f"unknown config key [{section}] {key}")
        current = getattr(target, key)
        if isinstance(current, bool) and not isinstance(value, bool):
            raise ConfigError(f"[{section}] {key} must be true/false")
        if isinstance(current, float) and isinstance(value, int) and not isinstance(value, bool):
            value = float(value)
        if isinstance(current, dt.time):
            if isinstance(value, str):
                try:
                    value = dt.time.fromisoformat(value)
                except ValueError as exc:
                    raise ConfigError(f"[{section}] {key} must be HH:MM") from exc
            elif not isinstance(value, dt.time):
                raise ConfigError(f"[{section}] {key} must be HH:MM")
        elif type(current) is not type(value):
            raise ConfigError(f"[{section}] {key} must be {type(current).__name__}")
        setattr(target, key, value)


def load_config(path: str | Path) -> Config:
    with open(path, "rb") as f:
        data = tomllib.load(f)
    cfg = Config()
    _apply(cfg, data, "top level")
    for name in ("relay", "schedule", "reactions", "laser", "web"):
        if name in data:
            if not isinstance(data[name], dict):
                raise ConfigError(f"[{name}] must be a table")
            _apply(getattr(cfg, name), data[name], name)
    unknown = {k for k, v in data.items() if isinstance(v, dict)} - {
        "relay", "schedule", "reactions", "laser", "web"
    }
    if unknown:
        raise ConfigError(f"unknown config section(s): {', '.join(sorted(unknown))}")
    validate(cfg)
    return cfg


def validate(cfg: Config) -> None:
    s = cfg.schedule
    for name in ("start_of_day", "end_of_day"):
        if not 0 <= getattr(s, name) <= 23:
            raise ConfigError(f"[schedule] {name} must be between 0 and 23")
    if s.dance_interval_minutes <= s.dance_jitter_minutes:
        raise ConfigError("[schedule] dance_interval_minutes must exceed dance_jitter_minutes")
    if cfg.laser.enabled and cfg.laser.command and any(
        "{midi}" in part for part in cfg.laser.command
    ) and not cfg.laser.midi_dir:
        raise ConfigError("[laser] command uses {midi} but midi_dir is not set")
