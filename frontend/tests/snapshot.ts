import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import type { Page } from "@playwright/test";

export async function snapshot(page: Page, name: string) {
  // Local DOM capture for the taste inspector; no session cookies or password values are exported.
  const body = await page.evaluate(() => {
    const clone = document.body.cloneNode(true) as HTMLElement;
    clone.querySelectorAll("script").forEach((node) => node.remove());
    clone
      .querySelectorAll<HTMLInputElement>('input[type="password"]')
      .forEach((node) => {
        node.value = "";
        node.removeAttribute("value");
      });
    return clone.innerHTML;
  });
  const css = readFileSync(
    new URL("../src/style.css", import.meta.url),
    "utf8",
  ).replace(/url\(["']?\/fonts\/([^"')]+)["']?\)/g, (_match, name: string) => {
    const font = readFileSync(
      new URL(`../public/fonts/${name}`, import.meta.url),
    );
    return `url("data:font/ttf;base64,${font.toString("base64")}")`;
  });
  const folder = new URL("../../artifacts/taste/snapshots/", import.meta.url);
  mkdirSync(folder, { recursive: true });
  writeFileSync(
    new URL(`${name}.html`, folder),
    `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RelayOps ${name}</title><style>${css}</style><body>${body}</body></html>`,
  );
}
