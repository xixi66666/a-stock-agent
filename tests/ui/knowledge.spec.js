const { test, expect } = require("@playwright/test");

test.beforeEach(async ({ page }) => {
  await page.route("**/api/agent/status", route => route.fulfill({ json: { enabled: false } }));
});

for (const [width, height] of [[1440, 1000], [1024, 768], [768, 1024], [390, 844]]) {
  test(`book knowledge works without market data at ${width}x${height}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    await page.goto("/");
    await page.getByText("书本知识库", { exact: true }).click();
    const panel = page.locator("#book-knowledge");
    await panel.getByRole("textbox", { name: "知识问题" }).fill("孕线的影线可以越界吗");
    await panel.getByRole("button", { name: "检索", exact: true }).click();
    await expect(panel.locator(".knowledge-results")).toContainText("孕线与十字孕线");
    await expect(panel.locator(".knowledge-results")).toContainText("第六章 其他反转形态");
    await expect(panel.locator(".knowledge-results")).toContainText("所需证据");
    await expect(panel.locator(".knowledge-results")).toContainText("适用边界");
    await panel.getByRole("combobox", { name: "书籍范围" }).selectOption("marks");
    await panel.getByRole("button", { name: "检索", exact: true }).click();
    await expect(panel.getByRole("status")).toContainText("未找到相关笔记");
    await panel.getByRole("textbox", { name: "知识问题" }).fill("信贷周期");
    await panel.getByRole("button", { name: "检索", exact: true }).click();
    await expect(panel.locator(".knowledge-results")).toContainText("融资松紧与信贷周期");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    const button = panel.getByRole("button", { name: "检索", exact: true });
    expect(await button.evaluate(el => el.scrollWidth <= el.clientWidth)).toBeTruthy();
    await panel.scrollIntoViewIfNeeded();
    expect((await panel.boundingBox()).y).toBeGreaterThanOrEqual(0);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath(`knowledge-${width}.png`), fullPage: true });
  });
}

test("book entry deep link and recoverable search failure", async ({ page }) => {
  await page.goto("/?knowledge=nison-harami#book-knowledge");
  const panel = page.locator("#book-knowledge");
  await expect(panel.locator(".knowledge-results")).toContainText("孕线与十字孕线");
  await page.route("**/api/knowledge/search**", route => route.fulfill({ status: 503, json: {} }));
  await panel.getByRole("textbox", { name: "知识问题" }).fill("窗口");
  await panel.getByRole("button", { name: "检索", exact: true }).click();
  await expect(panel.getByRole("status")).toContainText("检索暂不可用");
  await expect(panel.locator(".knowledge-results")).toBeEmpty();
  await page.unroute("**/api/knowledge/search**");
  await panel.getByRole("button", { name: "检索", exact: true }).click();
  await expect(panel.locator(".knowledge-results")).toContainText("窗口、回补与收盘失效");
});
