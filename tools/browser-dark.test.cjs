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
  constructableCss = false,
  mainCoverage = null,
  mainTop = 0,
  textColor = "rgb(40, 40, 40)",
  mainSelector = "main",
  sampledTone = null,
} = {}, session = new Map()) {
  const styles = new Map();
  const queued = [];
  const observers = [];
  const attrs = new Map();

  let rootWrites = 0;
  const root = {
    backgroundColor: rootColor,
    getAttribute: (name) => attrs.get(name) ?? null,
    hasAttribute: (name) => attrs.has(name),
    setAttribute: (name, value) => { rootWrites++; attrs.set(name, value); },
    removeAttribute: (name) => { rootWrites++; attrs.delete(name); },
  };
  const body = { backgroundColor: bodyColor };
  let mutableMainTop = mainTop;
  const main = {
    backgroundColor: mainColor,
    ...(mainCoverage !== null ? {
      getBoundingClientRect: () => ({
        left: 0, right: 390,
        top: mutableMainTop, bottom: mutableMainTop + 800 * mainCoverage,
        width: 390, height: 800 * mainCoverage,
      }),
    } : {}),
  };
  const head = { appendChild: (node) => styles.set(node.id, node) };

  const document = {
    documentElement: root,
    body,
    head,
    adoptedStyleSheets: constructableCss ? [] : undefined,
    querySelector: (selector) => selector === mainSelector ? main : null,
    elementFromPoint: sampledTone ? (x, y) => {
      const col = Math.max(0, Math.min(2, Math.floor((x / 390) * 3)));
      const row = Math.max(0, Math.min(2, Math.floor((y / 800) * 3)));
      const dark = sampledTone[row * 3 + col] === "dark";
      return {
        backgroundColor: dark ? "rgb(18, 20, 22)" : "rgb(252, 252, 252)",
        parentElement: body,
      };
    } : undefined,
    getElementById: (id) => styles.get(id) || null,
    createElement: () => ({ id: "", textContent: "" }),
    addEventListener: () => {},
  };
  const window = {
    innerWidth: 390,
    innerHeight: 800,
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

  class FakeStyleSheet {
    replaceSync(value) { this.css = value; }
  }
  const context = {
    document, window, MutationObserver,
    ...(constructableCss ? { CSSStyleSheet: FakeStyleSheet } : {}),
    getComputedStyle: (element) => ({
      backgroundColor: element.backgroundColor || "rgba(0, 0, 0, 0)",
      colorScheme,
      color: textColor,
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
    constructedCss: () => document.adoptedStyleSheets?.[0]?.css,
    body,
    root,
    main,
    setMainTop: (next) => { mutableMainTop = next; },
    rootWrites: () => rootWrites,
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
  assert.match(stylesheet, /svg/);
});

test("light pages become dark and protect embedded media", () => {
  const page = createPage();
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  const css = page.getStyle();
  assert.match(css, /img/);
  assert.match(css, /video/);
  assert.match(css, /canvas/);
  assert.match(css, /svg/);
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

test("large white main content beats a dark site shell", () => {
  const page = createPage({
    bodyColor: "rgb(24, 24, 24)",
    mainColor: "rgb(252, 252, 252)",
    mainCoverage: 0.85,
  });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
});

test("large dark main content beats a white outer frame", () => {
  const page = createPage({
    bodyColor: "rgb(252, 252, 252)",
    mainColor: "rgb(24, 24, 24)",
    mainCoverage: 0.85,
  });
  page.setMode("AUTO");
  assert.equal(page.active(), false);
});

test("offscreen white main content does not override a visible dark canvas", () => {
  const page = createPage({
    bodyColor: "rgb(22, 22, 22)",
    mainColor: "rgb(255, 255, 255)",
    mainCoverage: 2,
    mainTop: 1400,
  });
  page.setMode("AUTO");
  assert.equal(page.active(), false);
});

test("partially visible bright results must cover most of the viewport", () => {
  const page = createPage({
    bodyColor: "rgb(22, 22, 22)",
    mainColor: "rgb(255, 255, 255)",
    mainCoverage: 1,
    mainTop: 600,
  });
  page.setMode("AUTO");
  assert.equal(page.active(), false);
});

test("small white cards never force darkening on a dark page", () => {
  const page = createPage({
    bodyColor: "rgb(15, 15, 15)",
    mainColor: "rgb(255, 255, 255)",
    mainCoverage: 0.1,
  });
  page.setMode("AUTO");
  assert.equal(page.active(), false);
});

test("FORCE repairs light content hidden behind a dark color-scheme declaration", () => {
  const opts = {
    bodyColor: "rgba(0,0,0,0)",
    rootColor: "rgba(0,0,0,0)",
    mainColor: "rgba(0,0,0,0)",
    colorScheme: "dark",
    textColor: "rgb(18, 18, 18)",
  };
  const page = createPage(opts);
  page.setMode("AUTO");
  assert.equal(page.active(), false);
  page.setMode("FORCE");
  assert.equal(page.active(), true);
});

test("a true dark page remains dark in FORCE", () => {
  const page = createPage({
    bodyColor: "rgb(12, 14, 16)",
    textColor: "rgb(250, 250, 250)",
    colorScheme: "dark",
  });
  page.setMode("FORCE");
  assert.equal(page.active(), false);
});

test("Google lazy-load while scrolling does not flip the page theme", () => {
  const page = createPage({
    bodyColor: "rgb(24, 24, 24)",
    mainColor: "rgb(252, 252, 252)",
    mainCoverage: 0.9,
  });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  page.setMainTop(-1200);
  page.trigger(); // SPA appends results or changes a class during a scroll
  page.flush();
  assert.equal(page.active(), true);
});

test("a genuine page color change overrides the scroll-stable decision", () => {
  const page = createPage({
    bodyColor: "rgb(255, 255, 255)",
    mainColor: "rgba(0,0,0,0)",
  });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  page.body.backgroundColor = "rgb(17,17,17)";
  page.trigger();
  page.flush();
  assert.equal(page.active(), false);
});

test("Google-style #search container is recognized without a semantic main", () => {
  const page = createPage({
    bodyColor: "rgb(24, 24, 24)",
    mainColor: "rgb(250, 250, 250)",
    mainCoverage: 0.92,
    mainSelector: "#search",
  });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
});

test("large light div-based results are detected without a main tag", () => {
  const page = createPage({
    bodyColor: "rgb(18, 18, 18)",
    mainColor: "rgba(0,0,0,0)",
    mainSelector: "none",
    sampledTone: ["light","light","light", "light","dark","light", "light","light","dark"],
  });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
});

test("a small light card does not invert a dark page", () => {
  const page = createPage({
    bodyColor: "rgb(18, 18, 18)",
    mainSelector: "none",
    sampledTone: ["dark","dark","dark", "dark","light","dark", "dark","dark","dark"],
  });
  page.setMode("AUTO");
  assert.equal(page.active(), false);
});

test("busy SPA DOM updates do not repeatedly rewrite the filter attribute", () => {
  const page = createPage();
  page.setMode("AUTO");
  const initialWrites = page.rootWrites();
  for (let i = 0; i < 12; i++) {
    page.trigger();
    page.flush();
  }
  assert.equal(page.active(), true);
  assert.equal(page.rootWrites(), initialWrites);
});

test("modern WebView uses constructable CSS to avoid inline-style CSP", () => {
  const page = createPage({ constructableCss: true });
  page.setMode("AUTO");
  assert.equal(page.active(), true);
  assert.match(page.constructedCss(), /data-ybrowser-dark-active/);
  assert.equal(page.getStyle(), undefined);
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
