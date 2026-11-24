"""Command-line entry point: python -m totem config.toml"""

from __future__ import annotations

import argparse
import logging
import signal
import sys

from .config import ConfigError, load_config
from .engine import Engine
from .hardware import open_board
from .web import start_web


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="totem", description="Run the Totem controller.")
    parser.add_argument("config", help="path to the TOML config file")
    parser.add_argument("--simulate", action="store_true", help="use a simulated relay board")
    parser.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    log = logging.getLogger("totem")
    log.setLevel(logging.DEBUG if args.verbose else logging.INFO)

    try:
        cfg = load_config(args.config)
    except (OSError, ConfigError, ValueError) as exc:
        log.error("cannot load config %s: %s", args.config, exc)
        return 2

    port = "sim" if args.simulate else cfg.relay.port
    try:
        board = open_board(port, cfg.relay.baud)
    except Exception as exc:  # serial.SerialException, ImportError, ...
        log.error("cannot open relay board on %s: %s", port, exc)
        return 1

    engine = Engine(cfg, board)
    server = start_web(engine, cfg.web) if cfg.web.enabled else None

    def shutdown(*_):
        engine.stop()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    log.info("%s controller running", cfg.program_name)
    try:
        engine.run()
    finally:
        if server:
            server.shutdown()
        board.close()
        log.info("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
