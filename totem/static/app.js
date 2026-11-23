"use strict";

(() => {
  // Login page
  if (document.getElementById("error")) {
    if (new URLSearchParams(location.search).has("failed")) {
      document.getElementById("error").hidden = false;
    }
    return;
  }

  const $ = (id) => document.getElementById(id);
  const relayBody = document.querySelector("#relays tbody");
  let editing = false; // stop live updates overwriting checkboxes being edited
  let built = false;

  function cell(row, text) {
    const td = document.createElement("td");
    td.textContent = text;
    row.appendChild(td);
    return td;
  }

  function fillTable(tbody, rows) {
    tbody.replaceChildren(
      ...rows.map(([k, v]) => {
        const tr = document.createElement("tr");
        cell(tr, k);
        cell(tr, v);
        return tr;
      })
    );
  }

  function buildRelays(banks, readonly) {
    relayBody.replaceChildren();
    banks.forEach((_, bank) => {
      const tr = document.createElement("tr");
      cell(tr, String(bank + 1));
      const td = cell(tr, "");
      td.className = "bits";
      for (let bit = 0; bit < 8; bit++) {
        const box = document.createElement("input");
        box.type = "checkbox";
        box.dataset.bank = bank;
        box.dataset.bit = bit;
        box.title = `Bank ${bank + 1} relay ${bit}`;
        box.disabled = readonly;
        box.addEventListener("change", () => { editing = true; });
        td.appendChild(box);
      }
      relayBody.appendChild(tr);
    });
  }

  function showRelays(banks) {
    for (const box of relayBody.querySelectorAll("input")) {
      box.checked = Boolean((banks[box.dataset.bank] >> box.dataset.bit) & 1);
    }
  }

  function readRelays(count) {
    const banks = new Array(count).fill(0);
    for (const box of relayBody.querySelectorAll("input")) {
      if (box.checked) banks[box.dataset.bank] |= 1 << box.dataset.bit;
    }
    return banks;
  }

  async function post(path, body) {
    const res = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body ?? {}),
    });
    const data = await res.json().catch(() => ({}));
    $("message").textContent = res.ok ? "Sent." : `Error: ${data.error || res.status}`;
    if (res.status === 401) location.reload();
    return res.ok;
  }

  let banksCount = 19;

  async function refresh() {
    let s;
    try {
      const res = await fetch("/api/status");
      if (res.status === 401) return location.reload();
      s = await res.json();
    } catch {
      $("message").textContent = "Lost connection to the controller.";
      return;
    }
    const readonly = s.role !== "admin";
    document.body.classList.toggle("readonly", readonly);
    banksCount = s.banks.length;

    if (!built) {
      buildRelays(s.banks, readonly);
      $("sequence").replaceChildren(
        ...s.sequences.map(({ name, label }) => new Option(label, name))
      );
      built = true;
    }
    if (!editing) showRelays(s.banks);

    fillTable($("status"), [
      ["Time", s.time],
      ["Mode", s.mode],
      ["Operating hours", s.day_active ? "yes" : "no"],
      ["Mood", s.mood],
      ["Current sequence", s.current_sequence ?? "none"],
      ["Queued", String(s.queue.length)],
      ["Next dance", s.next_dance ?? "none"],
      ["Laser show", s.laser_show_running ? "running" : "idle"],
      ["Reactions this hour", String(s.reactions_this_hour)],
    ]);
    fillTable($("sensors"), s.sensors.map((v, i) => [s.sensor_labels[i] ?? `Channel ${i}`, String(v)]));
  }

  $("set-relays").addEventListener("click", async () => {
    if (await post("/api/override", { banks: readRelays(banksCount) })) editing = false;
  });
  $("reload-relays").addEventListener("click", () => { editing = false; refresh(); });
  $("run-sequence").addEventListener("click", () => post("/api/sequence", { name: $("sequence").value }));
  $("auto").addEventListener("click", () => post("/api/auto"));

  refresh();
  setInterval(refresh, 2000);
})();
