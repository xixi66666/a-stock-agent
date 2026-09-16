const { test, expect } = require('@playwright/test');
const snapshot = require('./fixtures/partial-snapshot.json');

test.beforeEach(async ({ page }) => {
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', route => route.fulfill({ json: { enabled: false } }));
  await page.route('**/api/stocks/600519/snapshot', route => route.fulfill({ json: snapshot }));
});

for (const viewport of [{ width: 1440, height: 1000 }, { width: 1024, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`cinematic home fits ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/');
    await expect(page.locator('h1')).toHaveText('让研究持续深入，让判断有据可循。');
    await expect(page.locator('.hero-card')).toHaveCount(2);
    const layout = await page.evaluate(() => {
      const main = document.querySelector('.hero-content').getBoundingClientRect();
      const nav = document.querySelector('.hero-nav').getBoundingClientRect();
      return { overflow: document.documentElement.scrollWidth > innerWidth || document.documentElement.scrollHeight > innerHeight,
        overlap: main.top < nav.bottom, bottom: main.bottom <= innerHeight,
        fit: [...document.querySelectorAll('button, .hero-cta')].every(el => el.scrollWidth <= el.clientWidth + 1) };
    });
    expect(layout).toEqual({ overflow: false, overlap: false, bottom: true, fit: true });
    await page.screenshot({ path: `target/ui-screenshots/landing-${viewport.width}x${viewport.height}.png` });
  });
}

test('hero search draws one pill-shaped focus ring instead of a box over the input', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/');
  const input = page.getByRole('textbox', { name: '六位股票代码' });
  await input.click();
  await input.fill('002354');
  const form = page.locator('.hero-form');
  await expect(form).toHaveCSS('border-radius', '999px');
  await expect(form).toHaveCSS('outline-style', 'solid');
  await expect(form).toHaveCSS('outline-offset', '4px');
  await expect(input).toHaveCSS('outline-style', 'none');
});

test('stock entry navigates and loads the requested stock', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('textbox', { name: '六位股票代码' }).fill('600519');
  await page.getByRole('button', { name: '开始研究', exact: true }).click();
  await expect(page).toHaveURL(/workbench.html\?code=600519/);
  await expect(page.locator('#security-code')).toHaveText('600519');
  await expect(page.locator('#latest-price')).not.toHaveText('--');
});

test('mobile drawer traps focus, closes with Escape, and restores focus', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  const toggle = page.getByRole('button', { name: '打开导航' });
  await toggle.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(toggle).toBeFocused();
});

test('reduced motion avoids automatic video downloads and invalid codes are ignored', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect(page.locator('.hero-video')).not.toHaveAttribute('src');
  await page.goto('/workbench.html?code=invalid&view=unknown');
  await expect(page.locator('#security-name')).toHaveText('等待选择股票');
});
