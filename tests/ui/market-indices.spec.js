const { test, expect } = require('@playwright/test');
const baseSnapshot = require('./fixtures/partial-snapshot.json');
const marketIndices = require('./fixtures/market-indices.json');

async function openWorkbench(page, indices) {
  await page.route('**/api/**', (route) => {
    const url = route.request().url();
    if (url.includes('/api/market/indices')) return route.fulfill({ json: indices });
    if (url.includes('/snapshot')) return route.fulfill({ json: baseSnapshot });
    return route.fulfill({ json: { status: 'UNAVAILABLE', enabled: false, issues: ['offline'] } });
  });
  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
  await expect(page.locator('#market-strip')).toBeVisible();
}

for (const viewport of [{width:1440,height:1000},{width:1024,height:768},{width:768,height:1024},{width:390,height:844}]) {
  test(`market strip renders indices at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openWorkbench(page, marketIndices);
    const strip = page.locator('#market-strip');
    await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"]')).toContainText('3,123.45');
    await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"]')).toContainText('↑ +0.33%');
    await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"] [data-field="state"]')).toBeHidden();
    await expect(strip.locator('[data-benchmark="SHENZHEN_COMPONENT"]')).toContainText('↓ -0.75%');
    await expect(strip.locator('[data-benchmark="SHENZHEN_COMPONENT"] [data-field="state"]')).toHaveText('收盘口径');
    await expect(strip.locator('[data-benchmark="CHI_NEXT"]')).toContainText('不可用');
    const layout = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }));
    expect(layout.scroll).toBeLessThanOrEqual(layout.width + 1);
    await page.screenshot({ path: `target/market-strip-${viewport.width}.png`, fullPage: true });
  });
}

test('market strip does not fabricate values when endpoint fails', async ({ page }) => {
  await page.route('**/api/**', (route) => {
    const url = route.request().url();
    if (url.includes('/api/market/indices')) return route.fulfill({ status: 500, json: { title: 'boom' } });
    if (url.includes('/snapshot')) return route.fulfill({ json: baseSnapshot });
    return route.fulfill({ json: { status: 'UNAVAILABLE', enabled: false, issues: ['offline'] } });
  });
  await page.goto('/workbench.html');
  await page.locator('[data-symbol="600519"]').click();
  const strip = page.locator('#market-strip');
  await expect(strip.locator('[data-benchmark="SHANGHAI_COMPOSITE"] [data-field="point"]')).toHaveText('不可用');
  await expect(strip).not.toContainText('0.00');
});
