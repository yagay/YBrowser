"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(
  path.join(__dirname, "../app/src/main/assets/ybrowser_reader/dark.js"),
  "utf8",
);

function createPage({
  bodyColor = "rgb(255, 255, 255)",
  rootColor = "rgba(0, 0, 0, 0)",
  mainColor = "rgba(0, 0, 0, 0)",
  colorScheme = "light",
} = {}, session = new Map()) {
  const styles = new Map();
  const queued = [];
  const observers = [];
  const attrs = new Map();

  const root = {
    backgroundColor: rootColor,
    getAttribute: (name) => attrs.get(name) ?? null,
    hasAttribute: (name) => attrs.has(name),
    setAttribute: (name, value) => attrs.set(name, value),
    removeAttribute: (name) => attrs.delete(name),
  };
  const body = { backgroundColor: bodyColor };
  const main = { backgroundColor: mainColor };
  const head = { appendChild: (node) => styles.set(node.id, node) };

  const document = {
    documentElement: root,
    body,
    head,
    querySelector: (selector) => selector === "main" ? main : null,
    getElementById: (id) => styles.get(id) || null,
    createElement: () => ({ id: "", textContent: "" }),
    addEventListener: () => {},
  };
  const window = {
    sessionStorage: {
      getItem: (key) => session.get(key) ?? null,
      setItem: (key, value) => session.set(key, String(value)),
    },
    addEventListener: () => {},
    matchMedia: () => ({ addEventListener: () => {} }),
  };
  class MutationObserver {
    constructor(callback) {
      this.callback = callback;
      observers.push(this);
    }
    observe() { this.connected = true; }
    disconnect() { this.connected = false; }
  }

  const context = {
    document, window, MutationObserver,
    getComputedStyle: (element) => ({
      backgroundColor: element.backgroundColor || "rgba(0, 0, 0, 0)",
      colorScheme,
    }),
    setTimeout: (callback) => {
      queued.push(callback);
      return queued.length;
    },
  };
  vm.runInNewContext(source, context, { filename: "dark.js", timeout: 2000 });
  return {
    active: () => attrs.get("data-ybrowser-dark-active") === "1",
    setMode: context.YBrowserDarkPage.setMode,
    getStyle: () => styles.get("ybrowser-dark-page-style")?.textContent,
    body,
    root,
    observers,
    session,
    flush: () => {
      while (queued.length) queued.shift()();
    },
    trigger: () => {
      for (const observer of observers) {
        if (observer.connected) {
          observer.callback([{
            target: body, type: "attributes", attributeName: "class",
          }]);
        }
      }
    },
  };
}

test("Gecko extension supplies declarative CSS for strict-CSP sites", () => {
  const assets = path.join(__dirname, "../app/src/main/assets/ybrowser_reader");
  const manifest = JSON.parse(fs.readFileSync(path.join(assets, "manifest.json"), "utf8"));
  assert.deepEqual(manifest.content_scripts[0].css, ["dark.css"]);
  assert.equal(manifest.content_scripts[0].run_at, "document_start");
  const stylesheet = fs.readFileSync(path.join(assets, "dark.css"), "utf8");
  assert.match(stylesheet, /data-ybrowser-dark-active/);
  assert.match(stylesheet, /img/);
  assert.match(stylesheet, /video/);
  assert.match(stylesheet, /canvas/);
});

test("light pages become dark and protect embedded media", () => {
  const page = createPage();
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  const css = page.getStyle();
  assert.match(css, /img/);
  assert.match(css, /video/);
  assert.match(css, /canvas/);
});

test("dark pages are never double-inverted, even with FORCE", () => {
  const page = createPage({ bodyColor: "rgb(16, 18, 22)" });
  page.setMode("FORCE");
  assert.equal(page.active(), false);
});

test("transparent canvas with a dark color-scheme is left alone", () => {
  const page = createPage({
    bodyColor: "rgba(0,0,0,0)",
    rootColor: "rgba(0,0,0,0)",
    colorScheme: "dark",
  });
  page.setMode("AUTO");
  assert.equal(page.active(), false);
});

test("site override OFF immediately releases the conversion", () => {
  const page = createPage();
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  page.setMode("OFF");
  assert.equal(page.active(), false);
  assert.equal(page.session.get("ybrowser.page.dark.mode.v1"), "OFF");
});

test("repeat visits restore page appearance without native message delay", () => {
  const session = new Map();
  const first = createPage({}, session);
  first.setMode("AUTO");
  const repeat = createPage({}, session);
  assert.equal(repeat.active(), true);
  repeat.setMode("OFF");
  assert.equal(repeat.active(), false);
});

test("SPA style changes trigger re-evaluation without reload", () => {
  const page = createPage();
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  page.body.backgroundColor = "rgb(20,20,20)";
  page.trigger();
  page.flush();
  assert.equal(page.active(), false);
});

test("display-p3 light colors do not get mistaken for dark backgrounds", () => {
  const page = createPage({ bodyColor: "color(display-p3 1 1 1)" });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
});

test("unsupported mode safely disables filtering", () => {
  const page = createPage();
  page.setMode("FORCE");
  page.setMode("unexpected-value");
  assert.equal(page.active(), false);
});
