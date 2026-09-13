const { test, expect } = require("@playwright/test");

test.beforeEach(async ({ page }) => {
  await page.route("**/api/**", route => route.fulfill({ json: { enabled: false, status: "DISABLED_CONFIGURATION_MISSING" } }));
});

for (const viewport of [
  { width: 1440, height: 1000 }, { width: 1024, height: 768 },
  { width: 768, height: 1024 }, { width: 390, height: 844 },
]) {
  test('research entry is readable at ' + viewport.width + 'x' + viewport.height, async ({ page }) => {
    await page.setViewportSize(viewport);
    const media = [];
    page.on('request', request => { if (request.url().endsWith('.mp4')) media.push(request.url()); });
    await page.goto('/workbench.html');
    await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(247, 247, 244)');
    await expect(page.locator('#search-submit')).toHaveCSS('background-color', 'rgb(35, 39, 37)');
    await expect(page.locator('.brand')).toContainText('a-stock');
    await expect(page.locator('.brand svg')).toBeVisible();
    await expect(page.locator('.workbench-backdrop')).toBeVisible();
    await expect(page.getByRole('heading', { name: '选择一只股票开始研究' })).toBeVisible();
    await expect(page.getByText('从行情出发，让每个判断都有依据。')).toBeVisible();
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > innerWidth,
      overlap: document.querySelector('.empty-state').getBoundingClientRect().top < document.querySelector('.app-header').getBoundingClientRect().bottom,
      buttonsFit: [...document.querySelectorAll('button')].every(el => el.scrollWidth <= el.clientWidth + 1),
      up: getComputedStyle(document.documentElement).getPropertyValue('--up').trim(),
      down: getComputedStyle(document.documentElement).getPropertyValue('--down').trim(),
    }));
    expect(layout).toEqual({ overflow: false, overlap: false, buttonsFit: true, up: '#d83b53', down: '#1f8a65' });
    expect(media).toEqual([]);
    await page.getByRole('searchbox').fill('600519');
    await expect(page.getByRole('searchbox')).toHaveValue('600519');
    await page.screenshot({ path: 'target/ui-screenshots/entry-' + viewport.width + 'x' + viewport.height + '.png', fullPage: true });
  });
}
