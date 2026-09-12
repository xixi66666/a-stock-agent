const { test, expect } = require("@playwright/test");

test.beforeEach(async ({ page }) => {
  await page.route("**/api/agent/status", (route) => route.fulfill({
    json: { enabled: false, status: "DISABLED_CONFIGURATION_MISSING" },
  }));
});

test("sunset actions use warm colors with readable text and preserve market color semantics", async ({ page }) => {
  await page.goto("/");
  const colors = await page.evaluate(() => {
    const button = getComputedStyle(document.querySelector("#search-submit"));
    const tokens = getComputedStyle(document.documentElement);
    return { background: button.backgroundColor, text: button.color,
      up: tokens.getPropertyValue("--up").trim(), down: tokens.getPropertyValue("--down").trim() };
  });
  const rgb = (color) => color.match(/[\d.]+/g).slice(0, 3).map(Number);
  const [red, green, blue] = rgb(colors.background);
  expect(red).toBeGreaterThan(green);
  expect(green).toBeGreaterThan(blue);
  const luminance = (color) => rgb(color).map((value) => {
    const linear = value / 255;
    return linear <= 0.04045 ? linear / 12.92 : ((linear + 0.055) / 1.055) ** 2.4;
  }).reduce((total, value, index) => total + value * [0.2126, 0.7152, 0.0722][index], 0);
  const levels = [luminance(colors.background), luminance(colors.text)].sort((a, b) => b - a);
  expect((levels[0] + 0.05) / (levels[1] + 0.05)).toBeGreaterThanOrEqual(4.5);
  expect(colors.up).toBe("#d83b53");
  expect(colors.down).toBe("#1f8a65");
});

test("footage keeps its original colors behind a light veil and readable research navigation", async ({ page }) => {
  await page.goto("/");
  const treatment = await page.evaluate(() => ({
    filter: getComputedStyle(document.querySelector(".ambient-video")).filter,
    veil: getComputedStyle(document.querySelector(".ambient-background"), "::after").backgroundColor,
    navigation: getComputedStyle(document.querySelector(".view-tabs")).backgroundColor,
  }));
  expect(treatment.filter).toBe("none");
  expect(Number(treatment.veil.match(/[\d.]+/g).at(-1))).toBeLessThan(0.3);
  expect(Number(treatment.navigation.match(/[\d.]+/g).at(-1))).toBeGreaterThanOrEqual(0.85);
});

test("background video plays silently and remembers the user's pause choice", async ({ page }) => {
  await page.goto("/");
  const video = page.locator(".ambient-video");
  await expect.poll(() => video.evaluate((el) => !el.paused && el.currentTime > 0)).toBe(true);
  expect(await video.evaluate((el) => el.muted && el.loop && el.playsInline)).toBe(true);
  await page.getByRole("button", { name: "暂停动态背景" }).click();
  await expect.poll(() => video.evaluate((el) => el.paused)).toBe(true);
  await page.reload();
  await expect(page.getByRole("button", { name: "播放动态背景" })).toBeVisible();
  expect(await video.evaluate((el) => el.paused)).toBe(true);
  await page.getByRole("button", { name: "播放动态背景" }).click();
  await expect.poll(() => video.evaluate((el) => !el.paused)).toBe(true);
});

test("reduced motion shows the poster without downloading video and reacts to preference changes", async ({ page }) => {
  const mediaRequests = [];
  page.on("request", (request) => { if (request.url().endsWith(".mp4")) mediaRequests.push(request.url()); });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  const video = page.locator(".ambient-video");
  await expect(page.getByRole("button", { name: "播放动态背景" })).toBeVisible();
  expect(await video.evaluate((el) => el.paused && !el.getAttribute("src"))).toBe(true);
  expect(mediaRequests).toEqual([]);
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await expect.poll(() => video.evaluate((el) => !el.paused)).toBe(true);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect.poll(() => video.evaluate((el) => el.paused)).toBe(true);
  await page.getByRole("button", { name: "播放动态背景" }).click();
  await expect.poll(() => video.evaluate((el) => !el.paused)).toBe(true);
});

test("failed footage falls back to its poster and stock search remains usable", async ({ page }) => {
  await page.route("**/media/*.mp4", (route) => route.abort());
  await page.goto("/");
  await expect(page.getByRole("button", { name: "动态背景暂不可用" })).toBeDisabled();
  const video = page.locator(".ambient-video");
  expect(await video.evaluate((el) => el.paused && !el.getAttribute("src"))).toBe(true);
  const poster = await video.evaluate((el) => new Promise((resolve) => {
    const image = new Image();
    image.onload = () => resolve(image.naturalWidth);
    image.onerror = () => resolve(0);
    image.src = el.poster;
  }));
  expect(poster).toBeGreaterThan(0);
  await page.getByRole("searchbox").fill("600519");
  await expect(page.getByRole("searchbox")).toHaveValue("600519");
});

test("hidden pages pause playback and returning preserves a manual pause", async ({ page }) => {
  await page.goto("/");
  const video = page.locator(".ambient-video");
  await expect.poll(() => video.evaluate((el) => !el.paused)).toBe(true);
  // Emulate the browser lifecycle event deterministically in headless Chrome.
  const visibility = (state) => page.evaluate((value) => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => value });
    document.dispatchEvent(new Event("visibilitychange"));
  }, state);
  await visibility("hidden");
  await expect.poll(() => video.evaluate((el) => el.paused)).toBe(true);
  await visibility("visible");
  await expect.poll(() => video.evaluate((el) => !el.paused)).toBe(true);
  await page.getByRole("button", { name: "暂停动态背景" }).click();
  await visibility("hidden");
  await visibility("visible");
  expect(await video.evaluate((el) => el.paused)).toBe(true);
});

for (const viewport of [
  { width: 1440, height: 1000 }, { width: 1024, height: 768 },
  { width: 768, height: 1024 }, { width: 390, height: 844 },
]) {
  test(`video landing remains readable at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/");
    await expect.poll(() => page.locator(".ambient-video").evaluate((el) => !el.paused && el.currentTime > 0)).toBe(true);
    await page.getByRole("button", { name: "暂停动态背景" }).click();
    const layout = await page.evaluate(() => {
      const header = document.querySelector(".app-header").getBoundingClientRect();
      const entry = document.querySelector(".empty-state").getBoundingClientRect();
      const background = document.querySelector(".ambient-background").getBoundingClientRect();
      return {
        overflow: document.documentElement.scrollWidth > innerWidth,
        overlap: entry.top < header.bottom,
        background: [background.width, background.height],
        buttonsFit: [...document.querySelectorAll("button")].every((button) => button.scrollWidth <= button.clientWidth + 1),
      };
    });
    expect(layout).toEqual({ overflow: false, overlap: false, background: [viewport.width, viewport.height], buttonsFit: true });
    await expect(page.getByRole("heading", { name: "选择一只股票开始研究" })).toBeVisible();
    await page.screenshot({ path: `target/ui-screenshots/background-${viewport.width}x${viewport.height}.png`, fullPage: true });
  });
}
