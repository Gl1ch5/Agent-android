"use strict";

// ---- helpers --------------------------------------------------------------

const ICONS = {
  back: '<path d="M19 12H5M12 19l-7-7 7-7"/>',
  x: '<path d="M18 6L6 18M6 6l12 12"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  sliders: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
  send: '<path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>',
  edit: '<path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
};

function ic(name) {
  const span = document.createElement("span");
  span.className = "ic";
  // Only our constant icon markup reaches innerHTML here, never user data.
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
  return span;
}

// h("tag", {props}, ...children). Text is always set with textContent, never innerHTML.
function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key === "value") node.value = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false || child === "") continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

const $ = (selector) => document.querySelector(selector);

function newId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

function pretty(value) {
  try {
    return JSON.stringify(typeof value === "string" ? JSON.parse(value) : value, null, 2);
  } catch (_) {
    return String(value);
  }
}

let toastTimer = 0;
function toast(message) {
  const node = $("#toast");
  node.textContent = message;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 4000);
}

// ---- bridge to Kotlin -----------------------------------------------------

const pending = new Map();
let requestSeq = 0;

function call(op, args = {}) {
  return new Promise((resolve) => {
    const id = String(++requestSeq);
    pending.set(id, resolve);
    window.Android.request(JSON.stringify({ id, op, ...args }));
  });
}

// Kotlin calls this with the result of a request.
window.__reply = (id, result) => {
  const resolve = pending.get(id);
  pending.delete(id);
  if (resolve) resolve(result);
};

// Resolves to the result, or null after showing the error.
async function ask(op, args) {
  const result = await call(op, args);
  if (result && result.error) {
    toast(result.error);
    return null;
  }
  return result;
}

// ---- state and routing ----------------------------------------------------

const S = {
  allFiles: true,
  projects: [],
  providers: [],
  activeId: null,
  mode: "CODE",
};

let route = { name: "projects" };
const history = [];
let renderToken = 0;
let liveTools = new Map();

function navigate(next) {
  history.push(route);
  route = next;
  render();
}

function back() {
  route = history.pop() || { name: "projects" };
  render();
}

// Android back button: close a sheet, then step back through screens.
window.handleBack = () => {
  if (!$("#sheet").hidden) {
    closeSheet();
    return true;
  }
  if (route.name === "projects") return false;
  back();
  return true;
};

async function refresh() {
  const state = await ask("state");
  if (!state) return;
  S.allFiles = state.allFiles;
  S.projects = state.projects;
  S.providers = state.providers;
  S.activeId = state.activeId;
}

async function render() {
  const token = ++renderToken;
  const view = $("#view");
  view.replaceChildren();
  $("#back").hidden = route.name === "projects";
  $("#settings").hidden = route.name !== "projects";

  if (route.name === "projects") {
    $("#title").textContent = "Projects";
    renderProjects(view);
  } else if (route.name === "project") {
    $("#title").textContent = route.project.name;
    await renderProject(view, route.project, token);
  } else if (route.name === "task") {
    await renderTask(view, route.project, route.taskId, token);
  }
}

// ---- projects -------------------------------------------------------------

function renderProjects(root) {
  if (!S.allFiles) {
    root.append(h("div", { class: "banner" },
      h("span", { text: "Folders outside the app need all-files access." }),
      h("button", { class: "secondary", onclick: () => ask("openAllFiles") }, "Grant"),
    ));
  }
  if (!S.projects.length) {
    root.append(h("p", { class: "muted", text: "No projects yet. Create one, or open a folder from your phone." }));
  }
  for (const project of S.projects) {
    root.append(h("article", { class: "card", onclick: () => navigate({ name: "project", project }) },
      h("div", { class: "card-main" },
        h("div", { class: "card-title", text: project.name }),
        h("div", { class: "muted small path", text: project.path }),
      ),
      h("button", {
        class: "icon-btn", "aria-label": "Remove project",
        onclick: (event) => { event.stopPropagation(); removeProject(project); },
      }, ic("trash")),
    ));
  }
  root.append(h("div", { class: "actions" },
    h("button", { class: "primary", onclick: openNewProject }, ic("plus"), "New project"),
    h("button", { class: "secondary", onclick: addFolder }, ic("folder"), "Open folder"),
  ));
}

function openNewProject() {
  const name = input("text", "", "Project name");
  openSheet("New project", [
    h("label", { class: "field" }, h("span", { text: "Name" }), name),
    h("button", {
      class: "primary",
      onclick: async () => {
        const project = await ask("createProject", { name: name.value });
        if (!project) return;
        closeSheet();
        await refresh();
        navigate({ name: "project", project });
      },
    }, "Create project"),
  ]);
  name.focus();
}

async function addFolder() {
  const picked = await ask("pickFolder");
  if (!picked || picked.cancelled) return;
  const project = await ask("addFolder", { path: picked.path });
  if (!project) return;
  await refresh();
  navigate({ name: "project", project });
}

async function removeProject(project) {
  if (!window.confirm(`Remove "${project.name}" from the list? Files stay on disk.`)) return;
  await ask("removeProject", { id: project.id });
  await refresh();
  render();
}

// ---- project: task list and composer -------------------------------------

async function renderProject(root, project, token) {
  const list = await ask("tasks", { projectId: project.id });
  if (token !== renderToken) return;
  const tasks = list ? list.tasks : [];

  root.append(h("div", { class: "muted small path", text: project.path }));

  const text = h("textarea", { rows: 3, placeholder: "Describe a task for this project" });
  const modes = ["ASK", "CODE"].map((mode) => h("button", {
    class: S.mode === mode ? "on" : "",
    onclick: (event) => {
      S.mode = mode;
      for (const button of event.currentTarget.parentNode.children) {
        button.classList.toggle("on", button.textContent === labelOf(mode));
      }
    },
  }, labelOf(mode)));

  root.append(h("section", { class: "composer" },
    text,
    h("div", { class: "row" },
      h("div", { class: "seg" }, modes),
      h("button", {
        class: "primary icon-only", "aria-label": "Start task",
        onclick: async () => {
          const message = text.value.trim();
          if (!message) return;
          const task = await ask("newTask", { projectId: project.id, mode: S.mode, text: message });
          if (task) navigate({ name: "task", project, taskId: task.id });
        },
      }, ic("send")),
    ),
  ));

  root.append(h("h2", { text: "Tasks" }));
  if (!tasks.length) root.append(h("p", { class: "muted small", text: "No tasks yet." }));
  for (const task of tasks) {
    root.append(h("article", { class: "card", onclick: () => navigate({ name: "task", project, taskId: task.id }) },
      h("div", { class: "card-main" },
        h("div", { class: "card-title", text: task.title }),
        h("div", { class: "meta" },
          badge(task.status),
          h("span", { class: "muted small", text: `${task.mode.toLowerCase()} · ${task.changes} changed` }),
        ),
      ),
    ));
  }
}

function labelOf(mode) {
  return mode === "ASK" ? "Ask" : "Code";
}

function badge(status) {
  return h("span", { class: `badge ${status.toLowerCase()}`, text: status.toLowerCase() });
}

// ---- task: transcript, changes, follow-up --------------------------------

async function renderTask(root, project, taskId, token) {
  const task = await ask("task", { projectId: project.id, taskId });
  if (token !== renderToken || !task) return;
  $("#title").textContent = task.title;

  root.append(h("div", { class: "meta" },
    badge(task.status),
    h("span", { class: "muted small", text: task.mode.toLowerCase() }),
  ));
  if (task.error) root.append(h("div", { class: "error", text: task.error }));

  const transcript = h("section", { class: "transcript", id: "live-root" });
  const tools = new Map();
  for (const message of task.history) renderMessage(transcript, message, tools);
  root.append(transcript);

  liveTools = new Map();
  root.append(h("section", { class: "transcript", id: "live" }));

  root.append(changesSection(task.changes));
  root.append(followUp(project, task));
  root.scrollTop = root.scrollHeight;
}

function renderMessage(container, message, tools) {
  for (const block of message.content) {
    if (block.type === "text") {
      container.append(bubble(message.role === "user" ? "user" : "assistant", block.text));
    } else if (block.type === "tool_use") {
      const card = toolCard(block.name, block.input);
      tools.set(block.id, card);
      container.append(card);
    } else if (block.type === "tool_result") {
      attachResult(tools.get(block.tool_use_id), block.content, block.is_error, container);
    }
  }
}

function bubble(role, text) {
  return h("div", { class: `bubble ${role}`, text });
}

function toolCard(name, input) {
  return h("details", { class: "tool" },
    h("summary", { text: name }),
    h("pre", { text: pretty(input) }),
  );
}

function attachResult(card, content, isError, fallback) {
  const result = h("pre", { class: isError ? "tool-result error" : "tool-result", text: content });
  if (card) card.append(result);
  else if (fallback) fallback.append(result);
  return result;
}

function changesSection(changes) {
  const section = h("section", { class: "changes" }, h("h2", { text: `Changes (${changes.length})` }));
  if (!changes.length) section.append(h("p", { class: "muted small", text: "No file changes." }));
  for (const change of changes) {
    const lines = change.diff
      ? h("pre", { class: "diff" }, change.diff.split("\n").map((line) => h("div", {
          class: line.startsWith("+ ") ? "add" : line.startsWith("- ") ? "del" : "",
          text: line,
        })))
      : h("p", { class: "muted small", text: "No text diff for this file." });
    section.append(h("details", { class: "change" },
      h("summary", {}, badge(change.status), h("span", { class: "mono", text: change.path })),
      lines,
    ));
  }
  return section;
}

function followUp(project, task) {
  const running = task.status === "RUNNING";
  const text = h("textarea", { rows: 2, placeholder: "Follow up", disabled: running });
  return h("section", { class: "followup" },
    text,
    h("div", { class: "row end" },
      running ? h("button", { class: "secondary", onclick: () => ask("cancel", { taskId: task.id }) }, ic("stop"), "Stop") : "",
      h("button", {
        class: "primary icon-only", "aria-label": "Send", disabled: running,
        onclick: async () => {
          const message = text.value.trim();
          if (!message) return;
          const result = await ask("followUp", { projectId: project.id, taskId: task.id, text: message });
          if (result) render();
        },
      }, ic("send")),
    ),
  );
}

// Live events from a running task, appended under the saved transcript.
function addLive(event) {
  const live = $("#live");
  if (!live) return;
  if (event.kind === "text") live.append(bubble("assistant", event.text));
  else if (event.kind === "toolStarted") {
    const card = toolCard(event.name, event.input);
    liveTools.set(event.id, card);
    live.append(card);
  } else if (event.kind === "toolFinished") {
    attachResult(liveTools.get(event.id), event.output, event.isError, live);
  } else if (event.kind === "error") {
    live.append(h("div", { class: "error", text: event.text }));
  }
  const view = $("#view");
  view.scrollTop = view.scrollHeight;
}

// Called by Kotlin for agent progress and for runs that have finished.
window.__event = (event) => {
  if (event.type === "taskFinished") {
    if ((route.name === "task" && route.taskId === event.taskId) || route.name === "project") render();
    return;
  }
  if (event.type === "agent" && route.name === "task" && route.taskId === event.taskId) addLive(event);
};

// ---- sheets: providers ----------------------------------------------------

function openSheet(title, children) {
  $("#sheetBody").replaceChildren(
    h("div", { class: "sheet-head" },
      h("h2", { text: title }),
      h("button", { class: "icon-btn", "aria-label": "Close", onclick: closeSheet }, ic("x")),
    ),
    ...children,
  );
  $("#sheet").hidden = false;
  $("#scrim").hidden = false;
}

function closeSheet() {
  $("#sheet").hidden = true;
  $("#scrim").hidden = true;
}

function input(type, value, placeholder) {
  const field = h("input", {
    type, placeholder, autocomplete: "off", autocapitalize: "off", spellcheck: "false",
  });
  field.value = value;
  return field;
}

async function openProviders() {
  await refresh();
  const list = h("div", { class: "list" });
  for (const provider of S.providers) {
    const active = provider.id === S.activeId;
    const kind = provider.kind === "ANTHROPIC" ? "Anthropic" : "OpenAI-compatible";
    list.append(h("article", { class: "card provider" },
      h("button", {
        class: active ? "radio on" : "radio", "aria-label": "Use this provider",
        onclick: async () => {
          const result = await ask("setActive", { id: provider.id });
          if (result) { S.activeId = result.activeId; openProviders(); }
        },
      }, active ? ic("check") : ""),
      h("div", { class: "card-main", onclick: () => editProvider(provider) },
        h("div", { class: "card-title", text: provider.name }),
        h("div", { class: "muted small", text: `${kind} · ${provider.selectedModel || "no model"}` }),
      ),
      h("button", { class: "icon-btn", "aria-label": "Edit", onclick: () => editProvider(provider) }, ic("edit")),
    ));
  }
  openSheet("Providers", [
    list,
    h("button", { class: "primary", onclick: () => editProvider(null) }, ic("plus"), "Add provider"),
  ]);
}

function editProvider(existing) {
  const draft = existing
    ? { ...existing, models: [...existing.models] }
    : { id: newId(), name: "", kind: "OPENAI_COMPATIBLE", baseUrl: "", apiKey: "", models: [], selectedModel: "" };
  let note = "";

  const paint = () => {
    const name = input("text", draft.name, "Name");
    const baseUrl = input("url", draft.baseUrl, "https://openrouter.ai/api/v1 or http://192.168.1.5:11434/v1");
    const apiKey = input("password", draft.apiKey, "API key");
    const manual = input("text", "", "Or enter one model ID");

    // Copy what is on screen into the draft before the form is rebuilt.
    const read = () => {
      draft.name = name.value.trim();
      draft.baseUrl = baseUrl.value.trim();
      draft.apiKey = apiKey.value.trim();
    };
    const kindButton = (kind, label) => h("button", {
      class: draft.kind === kind ? "on" : "",
      onclick: () => { read(); draft.kind = kind; note = ""; paint(); },
    }, label);

    const isOpenAi = draft.kind === "OPENAI_COMPATIBLE";
    const chips = h("div", { class: "chips" }, draft.models.map((model) => h("button", {
      class: model === draft.selectedModel ? "chip on" : "chip",
      onclick: () => { read(); draft.selectedModel = model; paint(); },
    }, model)));

    const payload = () => ({
      id: draft.id, name: draft.name, kind: draft.kind, baseUrl: draft.baseUrl,
      apiKey: draft.apiKey, models: draft.models, selectedModel: draft.selectedModel,
    });

    const fetchModels = async () => {
      read();
      const result = await call("fetchModels", { provider: payload() });
      if (result.error) {
        note = result.error;
      } else {
        note = "";
        draft.models = result.models;
        if (!draft.models.includes(draft.selectedModel)) draft.selectedModel = draft.models[0] || "";
      }
      paint();
    };

    const useManual = () => {
      const model = manual.value.trim();
      if (!model) return;
      read();
      draft.models = [...new Set([...draft.models, model])];
      draft.selectedModel = model;
      paint();
    };

    const save = async () => {
      read();
      const result = await call("saveProvider", { provider: payload() });
      if (result.error) {
        note = result.error;
        paint();
        return;
      }
      S.providers = result.providers;
      openProviders();
    };

    const remove = async () => {
      if (!window.confirm(`Delete provider "${draft.name || "this provider"}"?`)) return;
      const result = await ask("deleteProvider", { id: draft.id });
      if (!result) return;
      S.providers = result.providers;
      openProviders();
    };

    openSheet(existing ? "Edit provider" : "New provider", [
      h("label", { class: "field" }, h("span", { text: "Name" }), name),
      h("div", { class: "seg wide" },
        kindButton("OPENAI_COMPATIBLE", "OpenAI-compatible"),
        kindButton("ANTHROPIC", "Anthropic"),
      ),
      isOpenAi
        ? h("label", { class: "field" }, h("span", { text: "Base URL" }), baseUrl)
        : h("p", { class: "muted small", text: "Anthropic uses the official endpoint. Enter the model ID below." }),
      h("label", { class: "field" }, h("span", { text: "API key" }), apiKey),
      isOpenAi ? h("button", { class: "secondary", onclick: fetchModels }, "Add models from /v1/models") : "",
      note ? h("div", { class: "error", text: note }) : "",
      draft.models.length ? h("span", { class: "muted small", text: "Models" }) : "",
      chips,
      h("label", { class: "field" }, h("span", { text: "Or enter one model ID" }), manual),
      h("button", { class: "secondary", onclick: useManual }, "Use this model"),
      h("div", { class: "row" },
        existing ? h("button", { class: "danger", onclick: remove }, "Delete") : h("span"),
        h("div", { class: "row" },
          h("button", { class: "ghost", onclick: closeSheet }, "Cancel"),
          h("button", { class: "primary", onclick: save }, "Save"),
        ),
      ),
    ]);
  };

  paint();
}

// ---- start ----------------------------------------------------------------

async function init() {
  $("#back").replaceChildren(ic("back"));
  $("#settings").replaceChildren(ic("sliders"));
  $("#back").addEventListener("click", back);
  $("#settings").addEventListener("click", openProviders);
  $("#scrim").addEventListener("click", closeSheet);
  // Coming back from the system all-files settings page should show the new state.
  document.addEventListener("visibilitychange", async () => {
    if (document.visibilityState === "visible" && route.name === "projects") {
      await refresh();
      render();
    }
  });
  await refresh();
  render();
}

init();
