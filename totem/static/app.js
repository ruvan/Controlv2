"use strict";

(() => {
  // ---------------------------------------------------------------- login page
  if (document.querySelector(".login")) {
    if (new URLSearchParams(location.search).has("failed")) {
      document.getElementById("error").hidden = false;
    }
    return;
  }

  const $ = (id) => document.getElementById(id);
  const SVG = "http://www.w3.org/2000/svg";
  const W = 100;
  const H = 86.6;
  const NUM_BANKS = 19;
  const CONTROL = [
    { bit: 0, name: "Projection door", note: "Open / closed" },
    { bit: 1, name: "Lasers", note: "Laser power", confirm: "Switch the lasers on?" },
    { bit: 2, name: "Relay coil supply", note: "12 V for the petal relays" },
    { bit: 3, name: "Projection door power", note: "Door motor supply" },
  ];
  const WEATHER = [
    { ch: 6, label: "Wind 1" }, { ch: 7, label: "Wind 2" }, { ch: 8, label: "Rain" },
    { ch: 9, label: "Light" }, { ch: 10, label: "Aux 1" }, { ch: 11, label: "Aux 2" }, { ch: 12, label: "Aux 3" },
  ];
  const GROUP_ORDER = ["Patterns", "Animated", "Reactions", "Power", "Testing"];

  let status = null;
  let layout = null;
  let labels = {};
  let editing = false;
  let draft = null;
  let clockOffset = 0;
  let failures = 0;
  const petalEls = [];
  const bitEls = [];

  // ---------------------------------------------------------------- helpers
  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "class") node.className = v;
      else if (k === "text") node.textContent = v;
      else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v);
    }
    node.append(...children);
    return node;
  }

  function svg(tag, attrs = {}) {
    const node = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    return node;
  }

  function toast(message, error = false) {
    const t = el("div", { class: "toast" + (error ? " error" : ""), role: "status", text: message });
    $("toasts").append(t);
    setTimeout(() => t.remove(), error ? 6000 : 3500);
  }

  async function post(path, body) {
    try {
      const res = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body ?? {}),
      });
      if (res.status === 401) { location.reload(); return false; }
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { toast(data.error || `Request failed (${res.status})`, true); return false; }
      setTimeout(refresh, 250);
      return true;
    } catch {
      toast("Could not reach the controller.", true);
      return false;
    }
  }

  const isAdmin = () => status && status.role === "admin";
  const banks = () => (editing ? draft : status.banks);
  const bitOn = (bs, bank, bit) => Boolean((bs[bank] >> bit) & 1);
  const parseLocal = (iso) => new Date(iso).getTime(); // server sends local, zone-less times
  const serverNow = () => Date.now() + clockOffset;
  const pad = (n) => String(n).padStart(2, "0");
  const prettyName = (name) => labels[name] || (name || "").replace(/_/g, " ");

  function duration(ms) {
    if (ms <= 0) return "now";
    const s = Math.round(ms / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    if (h) return `${h}h ${pad(m)}m`;
    if (m) return `${m}m ${pad(s % 60)}s`;
    return `${s}s`;
  }

  // ---------------------------------------------------------------- totem drawing
  function vertices(f) {
    const x0 = f.number * (W / 2);
    const y0 = (2 - f.level) * H; // level 0 is the bottom row
    return f.up
      ? { A: [x0, y0 + H], B: [x0 + W, y0 + H], C: [x0 + W / 2, y0] }
      : { A: [x0, y0], B: [x0 + W, y0], C: [x0 + W / 2, y0 + H] };
  }

  // Which edge of the triangle each petal orientation lies on.
  const EDGES = { 5: ["A", "B"], 7: ["A", "C"], 3: ["B", "C"], 4: ["A", "B"], 6: ["A", "C"], 2: ["B", "C"] };

  function buildTotem() {
    const root = $("totem");
    root.replaceChildren();
    const defs = svg("defs");
    defs.innerHTML = `
      <linearGradient id="petal-on" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#ffd884"/><stop offset="1" stop-color="#f5a524"/>
      </linearGradient>
      <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="2.5" result="b"/>
        <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
      </filter>`;
    root.append(defs);

    for (const f of layout) {
      const v = vertices(f);
      const c = [(v.A[0] + v.B[0] + v.C[0]) / 3, (v.A[1] + v.B[1] + v.C[1]) / 3];
      const toward = (p, t) => [c[0] + (p[0] - c[0]) * t, c[1] + (p[1] - c[1]) * t];
      const group = svg("g");
      group.append(svg("polygon", {
        class: "flower-edge",
        points: [v.A, v.B, v.C].map((p) => p.join(",")).join(" "),
      }));
      for (const p of f.petals) {
        const [e1, e2] = EDGES[p.orientation].map((k) => v[k]);
        const mid = [(e1[0] + e2[0]) / 2, (e1[1] + e2[1]) / 2];
        const tip = [c[0] + (mid[0] - c[0]) * 0.14, c[1] + (mid[1] - c[1]) * 0.14];
        const poly = svg("polygon", {
          class: "petal",
          points: [tip, toward(e1, 0.9), toward(e2, 0.9)].map((q) => q.map((n) => n.toFixed(1)).join(",")).join(" "),
          tabindex: "-1",
        });
        const title = svg("title");
        title.textContent = `Level ${f.level + 1}, flower ${f.number + 1}, petal ${p.orientation} (bank ${p.bank + 1}, relay ${p.bit})`;
        poly.append(title);
        poly.dataset.bank = p.bank;
        poly.dataset.bit = p.bit;
        poly.addEventListener("click", () => togglePetal(p.bank, p.bit));
        poly.addEventListener("keydown", (e) => {
          if (e.key === "Enter" || e.key === " ") { e.preventDefault(); togglePetal(p.bank, p.bit); }
        });
        petalEls.push(poly);
        group.append(poly);
      }
      root.append(group);
    }

    // Motion sensors sit under the bottom row, below every second flower.
    const sy = 3 * H + 22;
    for (let i = 0; i < 6; i++) {
      const x = i * 2 * (W / 2) + W / 2;
      root.append(svg("circle", { class: "sensor-dot", cx: x, cy: sy, r: 6, id: `sensor-dot-${i}` }));
      const label = svg("text", { class: "axis-label", x, y: sy + 22, "text-anchor": "middle" });
      label.textContent = `M${i + 1}`;
      root.append(label);
    }
  }

  function togglePetal(bank, bit) {
    if (!isAdmin()) return;
    if (!editing) setEditing(true);
    draft[bank] ^= 1 << bit;
    render();
  }

  function setEditing(on) {
    editing = on;
    draft = on ? status.banks.slice() : null;
    $("view-live").setAttribute("aria-pressed", String(!on));
    $("view-edit").setAttribute("aria-pressed", String(on));
    $("edit-bar").hidden = !on;
    $("totem-card").classList.toggle("editing", on);
    for (const p of petalEls) p.setAttribute("tabindex", on ? "0" : "-1");
    render();
  }

  function editAction(action) {
    const forPetals = (fn) => { for (const p of petalEls) fn(+p.dataset.bank, +p.dataset.bit); };
    if (action === "cancel") return setEditing(false);
    if (action === "clear") forPetals((b, i) => { draft[b] &= ~(1 << i); });
    if (action === "all") forPetals((b, i) => { draft[b] |= 1 << i; });
    if (action === "invert") forPetals((b, i) => { draft[b] ^= 1 << i; });
    if (action === "apply") {
      post("/api/override", { banks: draft }).then((ok) => {
        if (ok) { toast("Pattern applied. Automatic mode paused."); setEditing(false); }
      });
      return;
    }
    render();
  }

  // ---------------------------------------------------------------- static widgets
  function buildRelayGrid() {
    const grid = $("relay-grid");
    grid.replaceChildren();
    for (let b = 0; b < NUM_BANKS; b++) {
      const bits = el("div", { class: "bits" });
      bitEls[b] = [];
      for (let i = 0; i < 8; i++) {
        const btn = el("button", {
          class: "bit" + (b > 0 && i === 0 ? " psu" : ""),
          title: `Bank ${b + 1}, relay ${i}`,
          "aria-label": `Bank ${b + 1} relay ${i}`,
          onclick: () => togglePetal(b, i),
        });
        bitEls[b].push(btn);
        bits.append(btn);
      }
      grid.append(el("div", { class: "bank" },
        el("header", {}, el("b", { text: `Bank ${b + 1}` }), el("span", { text: b === 0 ? "control" : `L${Math.floor((b - 1) / 6) + 1}` })),
        bits));
    }
  }

  function buildSwitches() {
    const list = $("switches");
    const row = (name, note, id, onToggle) => {
      const sw = el("button", { class: "switch", role: "switch", "aria-checked": "false", "aria-label": name, id, onclick: onToggle });
      return el("div", { class: "switch-row" }, el("div", { class: "name" }, el("span", { text: name }), el("small", { text: note })), sw);
    };
    for (const c of CONTROL) {
      list.append(row(c.name, c.note, `sw-${c.bit}`, () => {
        const next = banks().slice();
        const turningOn = !bitOn(next, 0, c.bit);
        if (turningOn && c.confirm && !confirm(c.confirm)) return;
        next[0] ^= 1 << c.bit;
        sendHardware(next, `${c.name} ${turningOn ? "on" : "off"}.`);
      }));
    }
    list.append(row("Petal power supplies", "All 18 petal-bank PSUs", "sw-psu", () => {
      const next = banks().slice();
      const on = !allPsusOn(next);
      for (let b = 1; b < NUM_BANKS; b++) next[b] = on ? next[b] | 1 : next[b] & ~1;
      sendHardware(next, `Power supplies ${on ? "on" : "off"}.`);
    }));
  }

  function sendHardware(next, message) {
    post("/api/override", { banks: next }).then((ok) => {
      if (ok) { toast(`${message} Automatic mode paused.`); if (editing) draft = next; }
    });
  }

  const allPsusOn = (bs) => bs.slice(1).every((v) => v & 1);

  function buildSequences(seqs) {
    labels = Object.fromEntries(seqs.map((s) => [s.name, s.label]));
    const groups = {};
    for (const s of seqs) (groups[s.group] ||= []).push(s);
    const root = $("sequences");
    root.replaceChildren();
    for (const g of GROUP_ORDER.filter((g) => groups[g])) {
      const chips = el("div", { class: "chips" });
      for (const s of groups[g]) {
        chips.append(el("button", {
          class: "chip", "data-seq": s.name, text: s.label,
          onclick: () => post("/api/sequence", { name: s.name }).then((ok) => ok && toast(`Running ${s.label}. Automatic mode paused.`)),
        }));
      }
      root.append(el("div", { class: "seq-group" }, el("h3", { text: g }), chips));
    }
  }

  function buildSensors() {
    const motion = $("motion");
    for (let i = 0; i < 6; i++) {
      motion.append(el("div", { class: "motion-col" },
        el("div", { class: "motion-bar", id: `mbar-${i}` }, el("i"), el("b")),
        el("span", { id: `mval-${i}`, text: "0" }),
        el("span", { text: `M${i + 1}` })));
    }
    const weather = $("weather");
    for (const w of WEATHER) {
      weather.append(el("div", { class: "weather-row" },
        el("span", { class: "label", text: w.label }),
        el("div", { class: "hbar" }, el("i", { id: `wbar-${w.ch}` })),
        el("span", { class: "value", id: `wval-${w.ch}`, text: "0" })));
    }
  }

  // ---------------------------------------------------------------- render
  function setPill(id, kind, text, live = false) {
    const pill = $(id);
    pill.hidden = false;
    pill.className = `pill ${kind}${live ? " live" : ""}`;
    pill.lastElementChild.textContent = text;
  }

  function render() {
    if (!status) return;
    const s = status;
    const bs = banks();
    const admin = isAdmin();

    // Totem + raw grid
    for (const p of petalEls) p.classList.toggle("on", bitOn(bs, +p.dataset.bank, +p.dataset.bit));
    for (let b = 0; b < NUM_BANKS; b++) {
      for (let i = 0; i < 8; i++) {
        const btn = bitEls[b][i];
        btn.classList.toggle("on", bitOn(bs, b, i));
        btn.disabled = !admin;
      }
    }
    for (let i = 0; i < 6; i++) {
      $(`sensor-dot-${i}`).classList.toggle("hot", s.sensors[i] > s.motion_threshold);
    }

    // Header
    if (s.mode === "auto") setPill("pill-mode", "ok", "Automatic", true);
    else setPill("pill-mode", "violet", "Manual control");
    if (s.shelter) setPill("pill-state", "bad", `Sheltering · ${s.shelter}`);
    else if (s.laser_show_running) setPill("pill-state", "violet", "Laser show", true);
    else if (s.mode === "auto" && s.day_active) setPill("pill-state", "ok", "Awake");
    else setPill("pill-state", "warn", s.in_hours ? "Paused" : "Resting");
    $("auto").hidden = !(admin && s.mode !== "auto");

    // Now playing
    const head = s.current_sequence;
    $("now-name").textContent = head ? prettyName(head) : "Holding pattern";
    let sub;
    if (s.mode === "manual") sub = "Manual control. Automatic dances and reactions are paused.";
    else if (s.shelter) sub = `Sheltering from the weather (${s.shelter}).`;
    else if (!s.day_active) sub = `Resting until ${pad(s.hours[0])}:00.`;
    else sub = head && s.queue[0] && s.queue[0].reaction ? "Reacting to someone nearby." : "Dancing on its own schedule.";
    $("now-sub").textContent = sub;
    const upcoming = s.queue.slice(head ? 1 : 0);
    const q = $("queue");
    q.replaceChildren(...upcoming.slice(0, 10).map((j, i) =>
      el("span", { class: "tag" + (j.reaction ? " reaction" : "") + (i === 0 ? " first" : ""), text: prettyName(j.name) })));
    if (upcoming.length > 10) q.append(el("span", { class: "tag", text: `+${upcoming.length - 10} more` }));
    if (!upcoming.length) q.append(el("span", { class: "empty", text: "Nothing queued" }));
    $("dances-left").textContent = s.dances_left;
    $("hours").textContent = `${pad(s.hours[0])}:00 – ${pad(s.hours[1])}:00`;
    $("reactions").textContent = `${s.reactions_this_hour} / ${s.reactions_per_hour}`;
    $("reactions-meter").style.width = `${Math.min(100, (100 * s.reactions_this_hour) / s.reactions_per_hour)}%`;
    tick();

    for (const chip of document.querySelectorAll(".chip")) chip.classList.toggle("active", chip.dataset.seq === head);

    // Sensors
    // Motion bars get their own scale so the trigger threshold sits at or below half height.
    const mscale = Math.max(2 * s.motion_threshold, ...s.sensors.slice(0, 6), 1);
    for (let i = 0; i < 6; i++) {
      const bar = $(`mbar-${i}`);
      bar.firstChild.style.height = `${(100 * s.sensors[i]) / mscale}%`;
      bar.lastChild.style.bottom = `${(100 * s.motion_threshold) / mscale}%`;
      bar.classList.toggle("hot", s.sensors[i] > s.motion_threshold);
      $(`mval-${i}`).textContent = s.sensors[i];
    }
    const scale = 2 ** Math.ceil(Math.log2(Math.max(1024, ...s.sensors.slice(6))));
    for (const w of WEATHER) {
      $(`wbar-${w.ch}`).style.width = `${(100 * s.sensors[w.ch]) / scale}%`;
      $(`wval-${w.ch}`).textContent = s.sensors[w.ch];
    }
    $("weather-mode").textContent = s.weather_enabled ? "Weather protection on" : "Weather protection off";

    // Hardware
    for (const c of CONTROL) {
      const sw = $(`sw-${c.bit}`);
      sw.setAttribute("aria-checked", String(bitOn(bs, 0, c.bit)));
      sw.disabled = !admin;
    }
    const psu = $("sw-psu");
    psu.setAttribute("aria-checked", String(allPsusOn(bs)));
    psu.disabled = !admin;
    $("laser-info").textContent = s.laser_show_running
      ? "Running now"
      : s.laser_enabled ? `Every evening at ${s.laser_time}` : "Scheduled show disabled";
    $("laser").disabled = s.laser_show_running;

    // Activity log
    $("log").replaceChildren(...s.events.slice().reverse().map((e) =>
      el("li", { class: e.level }, el("time", { text: e.time.slice(11) }), el("span", { text: e.message }))));
    if (!s.events.length) $("log").append(el("li", {}, el("time", { text: "" }), el("span", { class: "empty", text: "No activity yet" })));

    // Maintenance
    $("strokes").textContent = s.strokes_total.toLocaleString();
    const maxStrokes = Math.max(1, ...s.bank_strokes);
    const heat = $("heat");
    if (heat.children.length !== NUM_BANKS) heat.replaceChildren(...s.bank_strokes.map(() => el("i")));
    s.bank_strokes.forEach((n, b) => {
      heat.children[b].style.opacity = String(0.12 + (0.88 * n) / maxStrokes);
      heat.children[b].title = `Bank ${b + 1}: ${n.toLocaleString()} actuations`;
    });
  }

  // Updates every second between polls: clock and countdown.
  function tick() {
    if (!status) return;
    const now = new Date(serverNow());
    $("clock").textContent = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    $("next-dance").textContent = status.next_dance ? duration(parseLocal(status.next_dance) - serverNow()) : "—";
  }

  // ---------------------------------------------------------------- data
  async function refresh() {
    try {
      const res = await fetch("/api/status", { cache: "no-store" });
      if (res.status === 401) return location.reload();
      const s = await res.json();
      clockOffset = parseLocal(s.time) - Date.now();
      const first = !status;
      status = s;
      failures = 0;
      if (first) {
        document.body.classList.toggle("readonly", s.role !== "admin");
        buildSequences(s.sequences);
      }
      render();
    } catch {
      if (++failures >= 2) setPill("pill-mode", "bad", "Controller offline");
    }
  }

  async function init() {
    const res = await fetch("/api/layout");
    if (res.status === 401) return location.reload();
    layout = (await res.json()).flowers;
    buildTotem();
    buildRelayGrid();
    buildSwitches();
    buildSensors();

    $("view-live").addEventListener("click", () => setEditing(false));
    $("view-edit").addEventListener("click", () => setEditing(true));
    for (const b of document.querySelectorAll("[data-edit]")) b.addEventListener("click", () => editAction(b.dataset.edit));
    $("auto").addEventListener("click", () =>
      post("/api/auto").then((ok) => ok && toast("Resuming automatic mode.")));
    $("laser").addEventListener("click", () => {
      if (confirm("Start the laser show now? The projection door will open.")) {
        post("/api/laser").then((ok) => ok && toast("Laser show starting."));
      }
    });

    await refresh();
    setInterval(refresh, 1000);
    setInterval(tick, 1000);
  }

  init();
})();
